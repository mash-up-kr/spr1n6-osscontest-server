/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.document.repository

import com.osscontest.server.document.domain.Permission

/** 요청자 본인 또는 요청자 테넌트에 부여된 문서 권한 한 건. */
interface DocumentPermissionGrant {
    val documentId: Long
    val permission: Permission
}
