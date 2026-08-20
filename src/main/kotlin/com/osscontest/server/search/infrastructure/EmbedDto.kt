package com.osscontest.server.search.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** OpenAI Embeddings API(`POST /v1/embeddings`) 요청 계약. */
data class EmbedRequest(
    val input: String,
    val model: String,
    val dimensions: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbedResponse(
    val data: List<EmbeddingData> = emptyList(),
    val model: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbeddingData(
    val embedding: List<Float> = emptyList(),
    val index: Int? = null,
)
