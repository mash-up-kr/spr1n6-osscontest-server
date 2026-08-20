package com.osscontest.server.document.service

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.document.api.DocumentPermissionListResponse
import com.osscontest.server.document.api.DocumentPermissionResponse
import com.osscontest.server.document.api.GrantDocumentPermissionRequest
import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentAccessScope
import com.osscontest.server.document.domain.PrincipalType
import com.osscontest.server.document.repository.DocumentAccessScopeRepository
import com.osscontest.server.user.repository.AppUserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 문서별 권한 부여·회수. 소유자와 ADMIN 만 다룰 수 있다. */
@Service
class DocumentPermissionService(
    private val documentAccessChecker: DocumentAccessChecker,
    private val documentAccessScopeRepository: DocumentAccessScopeRepository,
    private val appUserRepository: AppUserRepository,
) {

    @Transactional(readOnly = true)
    fun listPermissions(authContext: AuthContext, documentId: Long): DocumentPermissionListResponse {
        val document = documentAccessChecker.requireAdministrable(authContext, documentId)

        val items = documentAccessScopeRepository.findByDocumentIdOrderByIdAsc(document.id!!)
            .map { it.toResponse() }

        return DocumentPermissionListResponse(items = items)
    }

    @Transactional
    fun grantPermission(
        authContext: AuthContext,
        documentId: Long,
        request: GrantDocumentPermissionRequest,
    ): DocumentPermissionResponse {
        val document = documentAccessChecker.requireAdministrable(authContext, documentId)
        validatePrincipal(authContext, request.principalType, request.principalId)

        val existing = documentAccessScopeRepository.findByDocumentIdAndPrincipalTypeAndPrincipalId(
            documentId = document.id!!,
            principalType = request.principalType,
            principalId = request.principalId,
        )

        val scope = existing?.apply { permission = request.permission }
            ?: documentAccessScopeRepository.save(newScope(authContext, document, request))

        return scope.toResponse()
    }

    @Transactional
    fun revokePermission(
        authContext: AuthContext,
        documentId: Long,
        principalType: PrincipalType,
        principalId: String,
    ) {
        val document = documentAccessChecker.requireAdministrable(authContext, documentId)

        if (principalType == PrincipalType.USER && principalId == document.ownerPrincipalId) {
            throw BusinessException(ErrorCode.OWNER_PERMISSION_NOT_REVOCABLE)
        }

        val scope = documentAccessScopeRepository.findByDocumentIdAndPrincipalTypeAndPrincipalId(
            documentId = document.id!!,
            principalType = principalType,
            principalId = principalId,
        )
            ?: throw BusinessException(ErrorCode.PERMISSION_NOT_FOUND)

        documentAccessScopeRepository.delete(scope)
    }

    private fun newScope(
        authContext: AuthContext,
        document: Document,
        request: GrantDocumentPermissionRequest,
    ): DocumentAccessScope =
        DocumentAccessScope(
            document = document,
            principalType = request.principalType,
            principalId = request.principalId,
            permission = request.permission,
            grantedByPrincipalId = authContext.userId.toString(),
        )

    /** 같은 테넌트에 실재하는 대상에만 권한을 부여한다. */
    private fun validatePrincipal(authContext: AuthContext, principalType: PrincipalType, principalId: String) {
        val id = principalId.toLongOrNull()
            ?: throw BusinessException(ErrorCode.PRINCIPAL_NOT_FOUND)

        val valid = when (principalType) {
            PrincipalType.USER -> appUserRepository.findTenantIdById(id) == authContext.tenantId
            PrincipalType.TENANT -> id == authContext.tenantId
        }

        if (!valid) {
            throw BusinessException(ErrorCode.PRINCIPAL_NOT_FOUND)
        }
    }

    private fun DocumentAccessScope.toResponse(): DocumentPermissionResponse =
        DocumentPermissionResponse(
            principalType = principalType,
            principalId = principalId,
            permission = permission,
        )
}
