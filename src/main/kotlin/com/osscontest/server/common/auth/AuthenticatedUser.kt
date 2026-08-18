package com.osscontest.server.common.auth

/** `X-User-Id` 헤더로부터 해석된 인증 컨텍스트. 클라이언트는 tenant 를 직접 보내지 않는다. */
data class AuthenticatedUser(
    val userId: Long,
    val tenantId: Long,
)
