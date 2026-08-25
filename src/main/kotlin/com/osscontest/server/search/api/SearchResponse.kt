package com.osscontest.server.search.api

import com.osscontest.server.search.domain.SearchResultItem

/** docs/API-SPEC.md 8장 `POST /api/v1/search` 200 응답 형태. */
data class SearchResponse(
    val items: List<SearchResultItem>,
)
