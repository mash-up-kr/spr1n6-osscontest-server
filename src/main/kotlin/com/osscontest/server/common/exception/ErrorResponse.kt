package com.osscontest.server.common.exception

/** docs/api-design.md 3장 "에러" 규약과 동일한 형태. */
data class ErrorResponse(
    val code: String,
    val message: String,
    val traceId: String,
)
