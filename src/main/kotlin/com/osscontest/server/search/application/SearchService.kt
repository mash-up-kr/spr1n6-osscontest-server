package com.osscontest.server.search.application

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.search.config.SearchProperties
import com.osscontest.server.search.domain.SearchResultItem
import com.osscontest.server.search.infrastructure.EmbeddingClient
import com.osscontest.server.search.infrastructure.SearchChunkRepository
import org.springframework.stereotype.Service

@Service
class SearchService(
    private val embeddingClient: EmbeddingClient,
    private val searchChunkRepository: SearchChunkRepository,
    private val searchProperties: SearchProperties,
) {

    /**
     * 설계 문서 5.3절 End-to-End 흐름 그대로:
     * 공백 제거 -> 질의 임베딩 -> (tsquery 변환은 리포지토리 SQL 안에서 수행) -> RRF 검색.
     * Re-rank(Cross-Encoder)는 초기 구현에 넣지 않기로 한 항목이라 이 서비스엔 없다.
     */
    fun search(user: AuthContext, request: SearchRequest): List<SearchResultItem> {
        val query = request.query.trim()
        if (query.isEmpty()) {
            throw BusinessException(ErrorCode.INVALID_QUERY)
        }

        val topK = (request.topK ?: searchProperties.defaultTopK)
            .coerceIn(1, searchProperties.maxTopK)
        val contextWindow = (request.contextWindow ?: 0)
            .coerceIn(0, searchProperties.maxContextWindow)

        val queryEmbedding = embeddingClient.embed(query)

        return searchChunkRepository.hybridSearch(
            tenantId = user.tenantId,
            userId = user.userId,
            queryText = query,
            queryEmbedding = queryEmbedding,
            topK = topK,
            contextWindow = contextWindow,
        )
    }
}

data class SearchRequest(
    val query: String,
    val topK: Int? = null,
    val contextWindow: Int? = null,
)
