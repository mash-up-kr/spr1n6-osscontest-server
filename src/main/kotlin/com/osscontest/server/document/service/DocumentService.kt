package com.osscontest.server.document.service

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.storage.ObjectStorage
import com.osscontest.server.common.trace.DbTraceIdBinder
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.common.web.Cursor
import com.osscontest.server.common.web.PageResponse
import com.osscontest.server.document.api.DocumentSummary
import com.osscontest.server.document.api.DocumentTitleResponse
import com.osscontest.server.document.api.DocumentUploadResponse
import com.osscontest.server.document.api.DocumentVersionDetailResponse
import com.osscontest.server.document.api.DocumentVersionSummary
import com.osscontest.server.document.api.IndexingProgress
import com.osscontest.server.document.api.ListDocumentVersionsRequest
import com.osscontest.server.document.api.ListDocumentsRequest
import com.osscontest.server.document.api.SearchableVersionResponse
import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentAccessScope
import com.osscontest.server.document.domain.DocumentVersion
import com.osscontest.server.document.domain.IndexingStatus
import com.osscontest.server.document.domain.Permission
import com.osscontest.server.document.domain.PrincipalType
import com.osscontest.server.document.domain.UploadFileType
import com.osscontest.server.document.repository.DocumentAccessScopeRepository
import com.osscontest.server.document.repository.DocumentRepository
import com.osscontest.server.document.repository.DocumentVersionRepository
import com.osscontest.server.tenant.repository.TenantRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class DocumentService(
    private val documentRepository: DocumentRepository,
    private val documentVersionRepository: DocumentVersionRepository,
    private val tenantRepository: TenantRepository,
    private val objectStorage: ObjectStorage,
    private val documentAccessChecker: DocumentAccessChecker,
    private val documentAccessScopeRepository: DocumentAccessScopeRepository,
    private val indexingStatusReader: IndexingStatusReader,
    private val dbTraceIdBinder: DbTraceIdBinder,
) {

    @Transactional
    fun createDocument(authContext: AuthContext, file: MultipartFile, title: String?): DocumentUploadResponse {
        val fileType = resolveFileType(file)
        dbTraceIdBinder.bind()

        val document = Document(
            tenant = tenantRepository.getReferenceById(authContext.tenantId),
            ownerPrincipalId = authContext.userId.toString(),
            title = title?.takeIf { it.isNotBlank() } ?: baseName(file),
        ).also {
            it.latestUploadVersionNo = FIRST_VERSION_NO
            documentRepository.save(it)
        }

        grantInitialPermissions(authContext, document)
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
        dbTraceIdBinder.bind()

        val document = findWritableDocumentForUpdate(authContext, documentId)

        val versionNo = document.latestUploadVersionNo + 1
        document.latestUploadVersionNo = versionNo

        // 중복 판정에 쓸 해시가 저장 전에 필요해 여기서 먼저 올린다. saveVersion 은 결과를 넘겨받아 다시 올리지 않는다.
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
        var cursorId = Cursor.decode(request.cursor, DOCUMENT_CURSOR_FIELD)
        val items = mutableListOf<DocumentSummary>()
        var nextCursor: String? = null

        // 인덱싱 상태와 검색 가능 여부는 다른 테이블을 조합해야 나오는 값이라 SQL 로 거르지 못한다.
        // 페이지를 읽어 애플리케이션에서 걸러내고, limit 을 채울 때까지 다음 페이지로 넘어간다.
        while (items.size <= request.limit) {
            val documents = findDocumentPage(authContext, cursorId, request.q)
            if (documents.isEmpty()) break

            val summaryContext = summaryContext(documents)
            for (document in documents) {
                val summary = document.toSummary(summaryContext)
                if (requestedStatus != null && summary.latestVersionIndexingStatus != requestedStatus) continue
                if (request.searchable != null && (summary.searchableVersionNo != null) != request.searchable) continue

                items += summary

                // limit 을 한 건 넘겼다는 것은 다음 페이지가 있다는 뜻이다. 마지막으로 담을 항목의 id 가 다음 커서가 된다.
                if (items.size > request.limit) {
                    nextCursor = Cursor.encode(DOCUMENT_CURSOR_FIELD, items[request.limit - 1].id)
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

        dbTraceIdBinder.bind()
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
            cursorVersionNo = Cursor.decode(request.cursor, VERSION_CURSOR_FIELD),
            pageable = PageRequest.of(0, request.limit + 1),
        )
        val versionContext = versionSummaryContext(versions)
        val items = versions.take(request.limit).map { it.toVersionSummary(document, versionContext) }
        val nextCursor = versions.getOrNull(request.limit)?.let {
            Cursor.encode(VERSION_CURSOR_FIELD, items.last().versionNo)
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
            indexing = IndexingProgress(indexingStatusReader.statusOf(version)),
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

    /**
     * 소유자에게 ADMIN, 소속 테넌트에 READ 를 부여한다.
     * 문서 접근과 검색이 모두 document_access_scope 만 보므로 생성 시점에 넣는다.
     */
    private fun grantInitialPermissions(authContext: AuthContext, document: Document) {
        documentAccessScopeRepository.saveAll(
            listOf(
                DocumentAccessScope(
                    document = document,
                    principalType = PrincipalType.USER,
                    principalId = authContext.userId.toString(),
                    permission = Permission.ADMIN,
                    grantedByPrincipalId = authContext.userId.toString(),
                ),
                DocumentAccessScope(
                    document = document,
                    principalType = PrincipalType.TENANT,
                    principalId = authContext.tenantId.toString(),
                    permission = Permission.READ,
                    grantedByPrincipalId = authContext.userId.toString(),
                ),
            ),
        )
    }

    private fun findReadableDocument(authContext: AuthContext, documentId: Long): Document =
        documentAccessChecker.requireReadable(authContext, documentId)

    private fun findWritableDocumentForUpdate(authContext: AuthContext, documentId: Long): Document =
        documentAccessChecker.requireWritableForUpdate(authContext, documentId)

    private fun findDocumentPage(authContext: AuthContext, cursorId: Long?, q: String?): List<Document> {
        val pageable = PageRequest.of(0, PAGE_SCAN_SIZE)
        val keyword = q?.takeIf { it.isNotBlank() }?.lowercase()
        val tenantPrincipalId = authContext.tenantId.toString()
        val userPrincipalId = authContext.userId.toString()

        return if (keyword == null) {
            documentRepository.findActivePage(
                tenantId = authContext.tenantId,
                cursorId = cursorId,
                tenantPrincipalId = tenantPrincipalId,
                userPrincipalId = userPrincipalId,
                pageable = pageable,
            )
        } else {
            documentRepository.findActivePageByTitle(
                tenantId = authContext.tenantId,
                cursorId = cursorId,
                titlePattern = "%${keyword.escapeLike()}%",
                tenantPrincipalId = tenantPrincipalId,
                userPrincipalId = userPrincipalId,
                pageable = pageable,
            )
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

    /** 목록 한 페이지에 붙일 부가 정보를 문서 수와 무관하게 세 번의 조회로 모은다. */
    private fun summaryContext(documents: List<Document>): DocumentSummaryContext {
        // 최신 버전 조회
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
        // 검색 대상 버전의 번호 조회
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
            indexingStatusByVersionId = indexingStatusReader.statusByVersionId(latestVersions.mapNotNull { it.id }),
        )
    }

    private fun versionSummaryContext(versions: List<DocumentVersion>): VersionSummaryContext =
        VersionSummaryContext(
            indexingStatusReader.statusByVersionId(versions.mapNotNull { it.id }),
        )

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

    private fun parseIndexingStatus(value: String): IndexingStatus =
        runCatching { IndexingStatus.valueOf(value.uppercase()) }
            .getOrElse { throw BusinessException(ErrorCode.INVALID_REQUEST) }

    private data class StoredObject(val key: String, val contentHash: String)

    companion object {
        private const val FIRST_VERSION_NO = 1L
        /** 요청 limit 의 상한(ListDocumentsRequest 의 @Max) 100 에 다음 페이지 존재 확인용 한 건을 더한 값. */
        private const val PAGE_SCAN_SIZE = 101
        private const val DOCUMENT_CURSOR_FIELD = "id"
        private const val VERSION_CURSOR_FIELD = "versionNo"
    }
}
