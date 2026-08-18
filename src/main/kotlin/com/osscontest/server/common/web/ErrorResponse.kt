package com.osscontest.server.common.web

data class ErrorResponse(
    val code: String,
    val message: String,
    val traceId: String?,
)
