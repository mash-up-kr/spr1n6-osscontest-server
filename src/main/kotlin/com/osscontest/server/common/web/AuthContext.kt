/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.common.web

/** X-User-Id 헤더로 만든 요청 단위 인증 정보. 컨트롤러가 테넌트를 얻는 통로. */
data class AuthContext(
    val userId: Long,
    val tenantId: Long,
)
