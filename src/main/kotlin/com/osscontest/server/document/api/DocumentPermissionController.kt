package com.osscontest.server.document.api

import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.document.domain.PrincipalType
import com.osscontest.server.document.service.DocumentPermissionService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/documents/{documentId}/permissions")
class DocumentPermissionController(
    private val documentPermissionService: DocumentPermissionService,
) {

    @GetMapping
    fun listPermissions(
        authContext: AuthContext,
        @PathVariable documentId: Long,
    ): DocumentPermissionListResponse =
        documentPermissionService.listPermissions(authContext, documentId)

    @PutMapping
    fun grantPermission(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @Valid @RequestBody request: GrantDocumentPermissionRequest,
    ): DocumentPermissionResponse =
        documentPermissionService.grantPermission(authContext, documentId, request)

    @DeleteMapping("/{principalType}/{principalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokePermission(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @PathVariable principalType: PrincipalType,
        @PathVariable principalId: String,
    ) {
        documentPermissionService.revokePermission(authContext, documentId, principalType, principalId)
    }
}
