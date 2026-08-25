package com.osscontest.server.search.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
import com.osscontest.server.search.config.SearchProperties
import com.osscontest.server.search.domain.RerankCandidate
import com.osscontest.server.search.domain.Reranker
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Cohere Rerank API(https://docs.cohere.com/reference/rerank)로 후보를 재정렬한다.
 * 범용 채팅 LLM에게 순서를 통째로 맡기는 listwise 방식 대신, query-문서 쌍마다 관련도를
 * 직접 채점하는 전용 랭킹 모델(Cross-Encoder류)을 쓴다 — 로컬 Cross-Encoder 비교 실험에서
 * 이 방식이 정확도·지연시간 모두 listwise LLM 재정렬보다 나았다.
 */
@Component
class CohereReranker(
    restClientBuilder: RestClient.Builder,
    private val searchProperties: SearchProperties,
) : Reranker {
    private val restClient = restClientBuilder.baseUrl(BASE_URL).build()

    override fun rerank(query: String, candidates: List<RerankCandidate>): List<Long> {
        if (candidates.isEmpty()) return emptyList()

        val cohereProps = searchProperties.rerank.cohere
        val response = restClient.post()
            .uri("/rerank")
            .header("Authorization", "Bearer ${cohereProps.apiKey}")
            .body(
                CohereRerankRequest(
                    model = cohereProps.model,
                    query = query,
                    documents = candidates.map { it.content },
                    topN = candidates.size,
                ),
            )
            .retrieve()
            .body(CohereRerankResponse::class.java)
            ?: throw IllegalStateException("Cohere rerank 응답이 비어 있습니다")

        return response.results
            .sortedByDescending { it.relevanceScore }
            .map { candidates[it.index].chunkId }
    }

    private data class CohereRerankRequest(
        val model: String,
        val query: String,
        val documents: List<String>,
        @JsonProperty("top_n") val topN: Int,
    )

    private data class CohereRerankResponse(
        val results: List<Result>,
    ) {
        data class Result(
            val index: Int,
            @JsonProperty("relevance_score") val relevanceScore: Double,
        )
    }

    private companion object {
        const val BASE_URL = "https://api.cohere.com/v2"
    }
}
