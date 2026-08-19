package com.osscontest.server.document.api

import com.osscontest.server.document.domain.Permission
import com.osscontest.server.document.domain.PrincipalType

data class DocumentPermissionResponse(
    val principalType: PrincipalType,
    val principalId: String,
    val permission: Permission,
)

data class DocumentPermissionListResponse(
    val items: List<DocumentPermissionResponse>,
)
