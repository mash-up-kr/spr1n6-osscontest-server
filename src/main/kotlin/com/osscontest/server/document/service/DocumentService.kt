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
import com.osscontest.server.document.api.IndexingProgress
import com.osscontest.server.document.api.ListDocumentVersionsRequest
import com.osscontest.server.document.api.ListDocumentsRequest
import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentVersion
import com.osscontest.server.document.domain.UploadFileType
import com.osscontest.server.document.repository.DocumentRepository
import com.osscontest.server.document.repository.DocumentVersionRepository
import com.osscontest.server.indexing.domain.IndexingJob
import com.osscontest.server.indexing.domain.IndexingStatus
import com.osscontest.server.indexing.repository.IndexingJobRepository
import com.osscontest.server.tenant.repository.TenantRepository
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

@Service
class DocumentService(
    private val documentRepository: DocumentRepository,
    private val documentVersionRepository: DocumentVersionRepository,
    private val indexingJobRepository: IndexingJobRepository,
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
        val document = documentRepository.findActive(documentId, authContext.tenantId)
            ?.takeIf { it.ownerPrincipalId == authContext.userId.toString() }
            ?: throw BusinessException(ErrorCode.DOCUMENT_NOT_FOUND)

        document.title = title.trim()

        return DocumentTitleResponse(id = document.id!!, title = document.title)
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
                ?.let { context.latestJobStatusByVersionId[it.id] }
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
            latestJobStatusByVersionId = latestJobStatusByVersionId(latestVersions.mapNotNull { it.id }),
        )
    }

    private fun versionSummaryContext(versions: List<DocumentVersion>): VersionSummaryContext =
        VersionSummaryContext(latestJobStatusByVersionId(versions.mapNotNull { it.id }))

    private fun latestJobStatusByVersionId(versionIds: Collection<Long>): Map<Long?, IndexingStatus> {
        if (versionIds.isEmpty()) return emptyMap()

        return indexingJobRepository.findByDocumentVersionIdIn(versionIds)
            .groupBy { it.documentVersionId }
            .mapValues { (_, jobs) -> jobs.latestByCreatedAt().status }
    }

    private fun List<IndexingJob>.latestByCreatedAt(): IndexingJob =
        maxWith(compareBy<IndexingJob> { it.createdAt }.thenBy { it.id })

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
            indexing = IndexingProgress(context.latestJobStatusByVersionId[id] ?: IndexingStatus.PENDING),
            searchable = document.searchableVersionId == id,
        )

    private data class DocumentSummaryContext(
        val latestVersionByDocumentId: Map<Long?, DocumentVersion>,
        val searchableVersionNoByVersionId: Map<Long?, Long>,
        val latestJobStatusByVersionId: Map<Long?, IndexingStatus>,
    )

    private data class VersionSummaryContext(
        val latestJobStatusByVersionId: Map<Long?, IndexingStatus>,
    )

    private fun indexingStatus(version: DocumentVersion): IndexingStatus =
        indexingJobRepository.findFirstByDocumentVersionIdOrderByCreatedAtDesc(version.id!!)?.status
            ?: IndexingStatus.PENDING

    private fun parseIndexingStatus(value: String): IndexingStatus =
        runCatching { IndexingStatus.valueOf(value.uppercase()) }
            .getOrElse { throw BusinessException(ErrorCode.INVALID_REQUEST) }

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
