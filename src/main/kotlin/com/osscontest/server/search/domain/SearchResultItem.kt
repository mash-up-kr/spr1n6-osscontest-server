package com.osscontest.server.search.domain

/**
 * 하이브리드 검색(RRF) 결과 한 건. 리포지토리 출력과 API 응답 아이템으로 그대로 겸용한다
 * (설계 문서 8장 `POST /api/v1/search` 응답 필드와 1:1 대응).
 */
data class SearchResultItem(
    val chunkId: Long,
    val documentId: Long,
    val title: String,
    val content: String,
    val contextBefore: List<String>,
    val contextAfter: List<String>,
    val score: Double,
    val pageFrom: Int?,
    val pageTo: Int?,
    val sectionPath: String?,
)
