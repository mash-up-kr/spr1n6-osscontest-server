package com.osscontest.server.common.web

data class PageResponse<T>(
    val items: List<T>,
    val nextCursor: String?,
)
