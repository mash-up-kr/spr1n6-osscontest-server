package com.osscontest.server.search.domain

/**
 * 하이브리드 검색 튜닝 파라미터 세 개를 묶는다.
 */
data class SearchOptions(
    val topK: Int,
    val contextWindow: Int,
    val efSearch: Int,
)
