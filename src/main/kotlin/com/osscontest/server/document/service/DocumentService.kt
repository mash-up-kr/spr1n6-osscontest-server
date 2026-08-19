package com.osscontest.server.document.service

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.storage.ObjectStorage
import com.osscontest.server.common.trace.TraceId
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.document.api.DocumentUploadResponse
import com.osscontest.server.document.api.IndexingProgressResponse
import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentVersion
import com.osscontest.server.document.domain.UploadFileType
import com.osscontest.server.document.repository.DocumentRepository
import com.osscontest.server.document.repository.DocumentVersionRepository
import com.osscontest.server.indexing.domain.IndexingStatus
import com.osscontest.server.tenant.repository.TenantRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

@Service
class DocumentService(
    private val documentRepository: DocumentRepository,
    private val documentVersionRepository: DocumentVersionRepository,
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
            indexing = IndexingProgressResponse(IndexingStatus.PENDING),
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
            indexing = IndexingProgressResponse(IndexingStatus.PENDING),
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
    }
}
