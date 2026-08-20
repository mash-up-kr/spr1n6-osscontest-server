package com.osscontest.server.document.service

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.Permission
import com.osscontest.server.document.repository.DocumentAccessScopeRepository
import com.osscontest.server.document.repository.DocumentRepository
import org.springframework.stereotype.Component

/**
 * 문서 접근 권한 검사.
 *
 * 문서를 찾지 못하면 404, 찾았지만 권한이 모자라면 403 으로 구분한다.
 * 다른 테넌트의 문서는 조회 단계에서 걸러지므로 404 가 된다.
 */
@Component
class DocumentAccessChecker(
    private val documentRepository: DocumentRepository,
    private val documentAccessScopeRepository: DocumentAccessScopeRepository,
) {

    fun requireReadable(authContext: AuthContext, documentId: Long): Document =
        require(authContext, findActive(authContext, documentId), Permission.READ)

    fun requireWritable(authContext: AuthContext, documentId: Long): Document =
        require(authContext, findActive(authContext, documentId), Permission.WRITE)

    /** 버전 번호를 순차적으로 부여하기 위해 문서 행을 잠근 뒤 검사한다. */
    fun requireWritableForUpdate(authContext: AuthContext, documentId: Long): Document =
        require(authContext, findActiveForUpdate(authContext, documentId), Permission.WRITE)

    fun requireAdministrable(authContext: AuthContext, documentId: Long): Document =
        require(authContext, findActive(authContext, documentId), Permission.ADMIN)

    /** 소유자는 ADMIN 으로 본다. 부여된 권한이 여러 건이면 가장 높은 것을 쓴다. */
    fun effectivePermission(authContext: AuthContext, document: Document): Permission? {
        if (document.ownerPrincipalId == authContext.userId.toString()) {
            return Permission.ADMIN
        }

        return documentAccessScopeRepository.findGrantedPermissions(
            documentId = document.id!!,
            tenantId = authContext.tenantId,
            tenantPrincipalId = authContext.tenantId.toString(),
            userPrincipalId = authContext.userId.toString(),
        ).maxByOrNull { it.level }
    }

    private fun require(authContext: AuthContext, document: Document, required: Permission): Document {
        val granted = effectivePermission(authContext, document)
        if (granted == null || !granted.satisfies(required)) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        return document
    }

    private fun findActive(authContext: AuthContext, documentId: Long): Document =
        documentRepository.findActive(documentId, authContext.tenantId)
            ?: throw BusinessException(ErrorCode.DOCUMENT_NOT_FOUND)

    private fun findActiveForUpdate(authContext: AuthContext, documentId: Long): Document =
        documentRepository.findActiveForUpdate(documentId, authContext.tenantId)
            ?: throw BusinessException(ErrorCode.DOCUMENT_NOT_FOUND)
}
