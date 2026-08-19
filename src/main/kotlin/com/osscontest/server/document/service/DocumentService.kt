package com.osscontest.server.document.service

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.storage.ObjectStorage
import com.osscontest.server.common.trace.TraceId
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.common.web.PageResponse
import com.osscontest.server.document.api.DocumentSummary
import com.osscontest.server.document.api.DocumentTitleResponse
import com.osscontest.server.document.api.DocumentUploadResponse
import com.osscontest.server.document.api.DocumentVersionDetailResponse
import com.osscontest.server.document.api.DocumentVersionSummary
import com.osscontest.server.document.api.IndexingRetryResponse
import com.osscontest.server.document.api.IndexingStatusResponse
import com.osscontest.server.document.api.IndexingProgress
import com.osscontest.server.document.api.ListDocumentVersionsRequest
import com.osscontest.server.document.api.ListDocumentsRequest
import com.osscontest.server.document.api.SearchableVersionResponse
import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentVersion
import com.osscontest.server.document.domain.UploadFileType
import com.osscontest.server.document.repository.DocumentRepository
import com.osscontest.server.document.repository.DocumentVersionRepository
import com.osscontest.server.indexing.domain.IndexingJob
import com.osscontest.server.indexing.domain.IndexingStatus
import com.osscontest.server.indexing.repository.IndexingJobRepository
import com.osscontest.server.outbox.domain.OutboxEvent
import com.osscontest.server.outbox.domain.OutboxEventType
import com.osscontest.server.outbox.domain.OutboxStatus
import com.osscontest.server.outbox.repository.OutboxEventRepository
import com.osscontest.server.tenant.repository.TenantRepository
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

@Service
class DocumentService(
    private val documentRepository: DocumentRepository,
    private val documentVersionRepository: DocumentVersionRepository,
    private val indexingJobRepository: IndexingJobRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val tenantRepository: TenantRepository,
    private val objectStorage: ObjectStorage,
    private val entityManager: EntityManager,
) {

    @Transactional
    fun createDocument(authContext: AuthContext, file: MultipartFile, title: String?): DocumentUploadResponse {
        val fileType = resolveFileType(file)
        passTraceIdToTrigger()

        val document = Document(
            tenant = tenantRepository.getReferenceById(authContext.tenantId),
            ownerPrincipalId = authContext.userId.toString(),
            title = title?.takeIf { it.isNotBlank() } ?: baseName(file),
        ).also {
            it.latestUploadVersionNo = FIRST_VERSION_NO
            documentRepository.save(it)
        }

        saveVersion(authContext, document, FIRST_VERSION_NO, file, fileType)

        return DocumentUploadResponse(
            documentId = document.id!!,
            versionNo = FIRST_VERSION_NO,
            duplicateOfVersionNo = null,
            indexing = IndexingProgress(IndexingStatus.PENDING),
        )
    }

    @Transactional
    fun addVersion(authContext: AuthContext, documentId: Long, file: MultipartFile): DocumentUploadResponse {
        val fileType = resolveFileType(file)
        passTraceIdToTrigger()

        val document = documentRepository.findActiveWritableForUpdate(
            id = documentId,
            tenantId = authContext.tenantId,
            tenantPrincipalId = authContext.tenantId.toString(),
            userPrincipalId = authContext.userId.toString(),
        )
            ?: throw BusinessException(ErrorCode.DOCUMENT_NOT_FOUND)

        val versionNo = document.latestUploadVersionNo + 1
        document.latestUploadVersionNo = versionNo

        val stored = upload(document, versionNo, file, fileType)
        val duplicateOfVersionNo =
            documentVersionRepository.findEarliestVersionNoByContentHash(documentId, stored.contentHash)

        saveVersion(authContext, document, versionNo, file, fileType, stored)

        return DocumentUploadResponse(
            documentId = documentId,
            versionNo = versionNo,
            duplicateOfVersionNo = duplicateOfVersionNo,
            indexing = IndexingProgress(IndexingStatus.PENDING),
        )
    }

    @Transactional(readOnly = true)
    fun listDocuments(
        authContext: AuthContext,
        request: ListDocumentsRequest,
    ): PageResponse<DocumentSummary> {
        val requestedStatus = request.indexingStatus?.let { parseIndexingStatus(it) }
        var cursorId = decodeCursor(request.cursor, DOCUMENT_CURSOR_FIELD)
        val items = mutableListOf<DocumentSummary>()
        var nextCursor: String? = null

        while (items.size <= request.limit) {
            val documents = findDocumentPage(authContext.tenantId, cursorId, request.q)
            if (documents.isEmpty()) break

            val summaryContext = summaryContext(documents)
            for (document in documents) {
                val summary = document.toSummary(summaryContext)
                if (requestedStatus != null && summary.latestVersionIndexingStatus != requestedStatus) continue
                if (request.searchable != null && (summary.searchableVersionNo != null) != request.searchable) continue

                items += summary
                if (items.size > request.limit) {
                    nextCursor = encodeCursor(DOCUMENT_CURSOR_FIELD, items[request.limit - 1].id)
                    break
                }
            }

            if (nextCursor != null || documents.size < PAGE_SCAN_SIZE) break
            cursorId = documents.last().id
        }

        return PageResponse(items = items.take(request.limit), nextCursor = nextCursor)
    }

    @Transactional(readOnly = true)
    fun getDocument(authContext: AuthContext, documentId: Long): DocumentSummary =
        findReadableDocument(authContext, documentId)
            .let { it.toSummary(summaryContext(listOf(it))) }

    @Transactional
    fun updateTitle(authContext: AuthContext, documentId: Long, title: String): DocumentTitleResponse {
        val document = findWritableDocumentForUpdate(authContext, documentId)

        document.title = title.trim()

        return DocumentTitleResponse(id = document.id!!, title = document.title)
    }

    @Transactional
    fun deleteDocument(authContext: AuthContext, documentId: Long) {
        val document = findWritableDocumentForUpdate(authContext, documentId)

        passTraceIdToTrigger()
        document.deletedAt = Instant.now()
    }

    @Transactional
    fun updateSearchableVersion(
        authContext: AuthContext,
        documentId: Long,
        versionNo: Long,
    ): SearchableVersionResponse {
        val document = findWritableDocumentForUpdate(authContext, documentId)
        val version = findVersion(document, versionNo)

        if (version.indexedAt == null) {
            throw BusinessException(ErrorCode.SEARCHABLE_VERSION_NOT_READY)
        }

        document.searchableVersionId = version.id

        return SearchableVersionResponse(searchableVersionNo = version.versionNo)
    }

    @Transactional(readOnly = true)
    fun listVersions(
        authContext: AuthContext,
        documentId: Long,
        request: ListDocumentVersionsRequest,
    ): PageResponse<DocumentVersionSummary> {
        val document = findReadableDocument(authContext, documentId)
        val versions = documentVersionRepository.findPageByDocumentId(
            documentId = document.id!!,
            cursorVersionNo = decodeCursor(request.cursor, VERSION_CURSOR_FIELD),
            pageable = PageRequest.of(0, request.limit + 1),
        )
        val versionContext = versionSummaryContext(versions)
        val items = versions.take(request.limit).map { it.toVersionSummary(document, versionContext) }
        val nextCursor = versions.getOrNull(request.limit)?.let {
            encodeCursor(VERSION_CURSOR_FIELD, items.last().versionNo)
        }

        return PageResponse(items = items, nextCursor = nextCursor)
    }

    @Transactional(readOnly = true)
    fun getVersion(authContext: AuthContext, documentId: Long, versionNo: Long): DocumentVersionDetailResponse {
        val document = findReadableDocument(authContext, documentId)
        val version = findVersion(document, versionNo)

        return DocumentVersionDetailResponse(
            versionNo = version.versionNo,
            originalFilename = version.originalFilename,
            mimeType = version.mimeType,
            fileSize = version.fileSize,
            uploadedAt = version.createdAt!!,
            indexing = IndexingProgress(indexingStatus(version)),
            searchable = document.searchableVersionId == version.id,
            sourceMetadata = version.sourceMetadata,
            extractedMetadata = version.extractedMetadata,
        )
    }

    @Transactional(readOnly = true)
    fun downloadVersion(authContext: AuthContext, documentId: Long, versionNo: Long): DocumentDownloadResult {
        val document = findReadableDocument(authContext, documentId)
        val version = findVersion(document, versionNo)

        return DocumentDownloadResult(
            originalFilename = version.originalFilename,
            mimeType = version.mimeType,
            fileSize = version.fileSize,
            content = objectStorage.get(version.sourceObjectKey),
        )
    }

    @Transactional(readOnly = true)
    fun getIndexingStatus(authContext: AuthContext, documentId: Long, versionNo: Long): IndexingStatusResponse {
        val document = findReadableDocument(authContext, documentId)
        val version = findVersion(document, versionNo)
        val indexing = indexingState(version)

        return IndexingStatusResponse(
            versionNo = version.versionNo,
            status = indexing.status,
            attemptCount = indexing.attemptCount,
            chunkCount = version.chunkCount,
            startedAt = indexing.startedAt,
            completedAt = indexing.completedAt,
            lastErrorMessage = indexing.lastErrorMessage,
        )
    }

    @Transactional
    fun retryIndexing(authContext: AuthContext, documentId: Long, versionNo: Long): IndexingRetryResponse {
        val document = findWritableDocumentForUpdate(authContext, documentId)
        val version = findVersion(document, versionNo)
        val latestEvent = latestIndexingRequestedEvent(version)
        val job = latestEvent?.let { indexingJobRepository.findBySourceEventId(it.id) }

        if (job?.status != IndexingStatus.FAILED) {
            throw BusinessException(ErrorCode.INDEXING_RETRY_NOT_ALLOWED)
        }
        if (hasPendingRetryEvent(version)) {
            throw BusinessException(ErrorCode.INDEXING_RETRY_ALREADY_REQUESTED)
        }

        val event = createRetryOutboxEvent(document, version, job.sourceEventId)
        notifyOutboxEvent(event.id)

        return IndexingRetryResponse(
            versionNo = version.versionNo,
            indexing = IndexingProgress(IndexingStatus.PENDING),
        )
    }

    private fun saveVersion(
        authContext: AuthContext,
        document: Document,
        versionNo: Long,
        file: MultipartFile,
        fileType: UploadFileType,
        uploaded: StoredObject? = null,
    ): DocumentVersion {
        val stored = uploaded ?: upload(document, versionNo, file, fileType)

        return DocumentVersion(
            document = document,
            versionNo = versionNo,
            sourceObjectKey = stored.key,
            originalFilename = file.originalFilename.orEmpty(),
            mimeType = fileType.mimeType,
            fileSize = file.size,
            contentHash = stored.contentHash,
            createdByPrincipalId = authContext.userId.toString(),
        ).also { documentVersionRepository.save(it) }
    }

    /** 저장소에 올리면서 같은 스트림으로 해시를 계산한다. 파일을 두 번 읽지 않는다. */
    private fun upload(
        document: Document,
        versionNo: Long,
        file: MultipartFile,
        fileType: UploadFileType,
    ): StoredObject {
        val key = objectKey(document, versionNo, fileType)
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream.use { input ->
            DigestInputStream(input, digest).use { hashing ->
                objectStorage.put(key, hashing, file.size, fileType.mimeType)
            }
        }

        return StoredObject(key, "sha256:" + digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun objectKey(document: Document, versionNo: Long, fileType: UploadFileType): String =
        "tenants/${document.tenant.id}/documents/${document.id}/versions/$versionNo/" +
            "${UUID.randomUUID()}.${fileType.extensions.first()}"

    private fun resolveFileType(file: MultipartFile): UploadFileType {
        if (file.isEmpty) {
            throw BusinessException(ErrorCode.EMPTY_FILE)
        }

        val extension = file.originalFilename?.substringAfterLast('.', "").orEmpty()

        return UploadFileType.ofExtension(extension)
            ?: throw BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE)
    }

    private fun baseName(file: MultipartFile): String =
        file.originalFilename?.substringBeforeLast('.').orEmpty().ifBlank { "제목 없음" }

    private fun findReadableDocument(authContext: AuthContext, documentId: Long): Document =
        documentRepository.findActive(documentId, authContext.tenantId)
            ?: throw BusinessException(ErrorCode.DOCUMENT_NOT_FOUND)

    private fun findWritableDocumentForUpdate(authContext: AuthContext, documentId: Long): Document =
        documentRepository.findActiveWritableForUpdate(
            id = documentId,
            tenantId = authContext.tenantId,
            tenantPrincipalId = authContext.tenantId.toString(),
            userPrincipalId = authContext.userId.toString(),
        )
            ?: throw BusinessException(ErrorCode.DOCUMENT_NOT_FOUND)

    private fun findDocumentPage(tenantId: Long, cursorId: Long?, q: String?): List<Document> {
        val pageable = PageRequest.of(0, PAGE_SCAN_SIZE)
        val keyword = q?.takeIf { it.isNotBlank() }?.lowercase()

        return if (keyword == null) {
            documentRepository.findActivePage(tenantId, cursorId, pageable)
        } else {
            documentRepository.findActivePageByTitle(tenantId, cursorId, "%${keyword.escapeLike()}%", pageable)
        }
    }

    private fun String.escapeLike(): String =
        replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun findVersion(document: Document, versionNo: Long): DocumentVersion =
        documentVersionRepository.findByDocumentIdAndVersionNo(document.id!!, versionNo)
            ?: throw BusinessException(ErrorCode.DOCUMENT_VERSION_NOT_FOUND)

    private fun Document.toSummary(context: DocumentSummaryContext): DocumentSummary {
        val latestVersion = context.latestVersionByDocumentId[id]

        return DocumentSummary(
            id = id!!,
            title = title,
            latestUploadVersionNo = latestUploadVersionNo,
            latestEmbeddingVersionNo = latestEmbeddingVersionNo,
            searchableVersionNo = context.searchableVersionNoByVersionId[searchableVersionId],
            latestVersionIndexingStatus = latestVersion
                ?.let { context.indexingStatusByVersionId[it.id] }
                ?: IndexingStatus.PENDING,
            createdAt = createdAt!!,
        )
    }

    private fun summaryContext(documents: List<Document>): DocumentSummaryContext {
        val documentIds = documents.mapNotNull { it.id }
        val latestVersionNos = documents.map { it.latestUploadVersionNo }.filter { it > 0 }.toSet()
        val latestVersions = if (documentIds.isEmpty() || latestVersionNos.isEmpty()) {
            emptyList()
        } else {
            documentVersionRepository.findByDocumentIdInAndVersionNoIn(documentIds, latestVersionNos)
        }
        val latestVersionByDocumentId = latestVersions
            .filter { version ->
                documents.any { document ->
                    document.id == version.document.id && document.latestUploadVersionNo == version.versionNo
                }
            }
            .associateBy { it.document.id }
        val searchableVersionIds = documents.mapNotNull { it.searchableVersionId }.toSet()
        val searchableVersionNoByVersionId = if (searchableVersionIds.isEmpty()) {
            emptyMap()
        } else {
            documentVersionRepository.findAllById(searchableVersionIds)
                .associate { it.id to it.versionNo }
        }

        return DocumentSummaryContext(
            latestVersionByDocumentId = latestVersionByDocumentId,
            searchableVersionNoByVersionId = searchableVersionNoByVersionId,
            indexingStatusByVersionId = indexingStateByVersionId(latestVersions.mapNotNull { it.id })
                .mapValues { it.value.status },
        )
    }

    private fun versionSummaryContext(versions: List<DocumentVersion>): VersionSummaryContext =
        VersionSummaryContext(
            indexingStateByVersionId(versions.mapNotNull { it.id }).mapValues { it.value.status },
        )

    private fun indexingStateByVersionId(versionIds: Collection<Long>): Map<Long?, IndexingState> {
        if (versionIds.isEmpty()) return emptyMap()

        val latestEventByVersionId = latestIndexingRequestedEventByVersionId(versionIds)
        if (latestEventByVersionId.isEmpty()) return emptyMap()

        val jobBySourceEventId = indexingJobRepository.findBySourceEventIdIn(latestEventByVersionId.values.map { it.id })
            .associateBy { it.sourceEventId }

        return latestEventByVersionId.mapValues { (_, event) ->
            jobBySourceEventId[event.id]?.toIndexingState() ?: event.toPendingIndexingState()
        }
    }

    private fun latestIndexingRequestedEventByVersionId(versionIds: Collection<Long>): Map<Long?, OutboxEvent> =
        outboxEventRepository.findByDocumentVersionIdInAndEventType(
            documentVersionIds = versionIds,
            eventType = OutboxEventType.INDEXING_REQUESTED,
        )
            .groupBy { it.documentVersionId }
            .mapValues { (_, events) -> events.latestByCreatedAt() }

    private fun List<OutboxEvent>.latestByCreatedAt(): OutboxEvent =
        maxWith(compareBy<OutboxEvent> { it.createdAt }.thenBy { it.id })

    private fun DocumentVersion.toVersionSummary(
        document: Document,
        context: VersionSummaryContext,
    ): DocumentVersionSummary =
        DocumentVersionSummary(
            versionNo = versionNo,
            originalFilename = originalFilename,
            mimeType = mimeType,
            fileSize = fileSize,
            uploadedAt = createdAt!!,
            indexing = IndexingProgress(context.indexingStatusByVersionId[id] ?: IndexingStatus.PENDING),
            searchable = document.searchableVersionId == id,
        )

    private data class DocumentSummaryContext(
        val latestVersionByDocumentId: Map<Long?, DocumentVersion>,
        val searchableVersionNoByVersionId: Map<Long?, Long>,
        val indexingStatusByVersionId: Map<Long?, IndexingStatus>,
    )

    private data class VersionSummaryContext(
        val indexingStatusByVersionId: Map<Long?, IndexingStatus>,
    )

    private data class IndexingState(
        val status: IndexingStatus,
        val attemptCount: Int = 0,
        val startedAt: Instant? = null,
        val completedAt: Instant? = null,
        val lastErrorMessage: String? = null,
    )

    private fun indexingStatus(version: DocumentVersion): IndexingStatus =
        indexingState(version).status

    private fun indexingState(version: DocumentVersion): IndexingState {
        val latestEvent = latestIndexingRequestedEvent(version)
        val job = latestEvent?.let { indexingJobRepository.findBySourceEventId(it.id) }

        return job?.toIndexingState()
            ?: latestEvent?.toPendingIndexingState()
            ?: IndexingState(status = IndexingStatus.PENDING)
    }

    private fun IndexingJob.toIndexingState(): IndexingState =
        IndexingState(
            status = status,
            attemptCount = attemptCount,
            startedAt = startedAt,
            completedAt = completedAt,
            lastErrorMessage = lastErrorMessage,
        )

    private fun OutboxEvent.toPendingIndexingState(): IndexingState =
        IndexingState(
            status = IndexingStatus.PENDING,
            lastErrorMessage = when {
                status == OutboxStatus.DEAD -> lastErrorMessage
                status == OutboxStatus.PENDING && publishAttemptCount > 0 -> lastErrorMessage
                else -> null
            },
        )

    private fun parseIndexingStatus(value: String): IndexingStatus =
        runCatching { IndexingStatus.valueOf(value.uppercase()) }
            .getOrElse { throw BusinessException(ErrorCode.INVALID_REQUEST) }

    private fun hasPendingRetryEvent(version: DocumentVersion): Boolean =
        outboxEventRepository.existsRetryEvent(
            documentVersionId = version.id!!,
            eventType = OutboxEventType.INDEXING_REQUESTED,
            statuses = listOf(OutboxStatus.PENDING, OutboxStatus.PUBLISHING),
        )

    private fun latestIndexingRequestedEvent(version: DocumentVersion): OutboxEvent? =
        outboxEventRepository.findFirstByDocumentVersionIdAndEventTypeOrderByCreatedAtDesc(
            documentVersionId = version.id!!,
            eventType = OutboxEventType.INDEXING_REQUESTED,
        )

    private fun createRetryOutboxEvent(
        document: Document,
        version: DocumentVersion,
        retryOfEventId: UUID,
    ): OutboxEvent {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val event = OutboxEvent(
            id = UUID.randomUUID(),
            tenantId = document.tenant.id!!,
            documentId = document.id!!,
            eventType = OutboxEventType.INDEXING_REQUESTED,
            payload = mapOf(
                "tenantId" to document.tenant.id!!,
                "versionNo" to version.versionNo,
                "sourceObjectKey" to version.sourceObjectKey,
                "mimeType" to version.mimeType,
                "fileSize" to version.fileSize,
                "contentHash" to version.contentHash,
                "occurredAt" to now.toString(),
            ),
        )

        event.documentVersionId = version.id
        event.retryOfEventId = retryOfEventId
        event.traceId = TraceId.current()
        event.nextAttemptAt = now

        return outboxEventRepository.saveAndFlush(event)
    }

    private fun notifyOutboxEvent(eventId: UUID) {
        entityManager.createNativeQuery("SELECT pg_notify('outbox_event', :eventId)")
            .setParameter("eventId", eventId.toString())
            .singleResult
    }

    private fun encodeCursor(field: String, value: Long): String {
        val json = """{"$field":$value}"""
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    private fun decodeCursor(cursor: String?, field: String): Long? {
        if (cursor.isNullOrBlank()) return null

        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(cursor))
            val value = json.substringAfter("\"$field\":", missingDelimiterValue = "")
                .substringBefore("}")
                .trim()

            value.toLong()
        }.getOrElse { throw BusinessException(ErrorCode.INVALID_REQUEST) }
    }

    /** 트리거는 애플리케이션 컨텍스트를 못 보므로 트랜잭션 설정으로 넘긴다. */
    private fun passTraceIdToTrigger() {
        val traceId = TraceId.current() ?: return

        entityManager.createNativeQuery("SELECT set_config('app.trace_id', :traceId, true)")
            .setParameter("traceId", traceId)
            .singleResult
    }

    private data class StoredObject(val key: String, val contentHash: String)

    companion object {
        private const val FIRST_VERSION_NO = 1L
        private const val PAGE_SCAN_SIZE = 101
        private const val DOCUMENT_CURSOR_FIELD = "id"
        private const val VERSION_CURSOR_FIELD = "versionNo"
    }
}
