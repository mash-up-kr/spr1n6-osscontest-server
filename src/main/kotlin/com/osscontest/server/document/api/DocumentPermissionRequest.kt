package com.osscontest.server.document.api

import com.osscontest.server.document.domain.Permission
import com.osscontest.server.document.domain.PrincipalType
import jakarta.validation.constraints.NotBlank

data class GrantDocumentPermissionRequest(
    val principalType: PrincipalType,
    @field:NotBlank(message = "principalId는 비어 있을 수 없습니다.")
    val principalId: String,
    val permission: Permission,
)
