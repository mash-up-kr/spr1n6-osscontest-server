/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.common.web

data class ErrorResponse(
    val code: String,
    val message: String,
    val traceId: String?,
)
