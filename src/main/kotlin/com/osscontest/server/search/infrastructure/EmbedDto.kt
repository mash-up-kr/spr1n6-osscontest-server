package com.osscontest.server.search.infrastructure

/** 그룹2 임베딩 서버(`POST /embed`) 계약. 검색은 항상 texts 단건(배치 크기 1)만 보낸다. */
data class EmbedRequest(
    val texts: List<String>,
    val model: String? = null,
)

data class EmbedResponse(
    val model: String? = null,
    val dim: Int? = null,
    val vectors: List<List<Float>> = emptyList(),
)
