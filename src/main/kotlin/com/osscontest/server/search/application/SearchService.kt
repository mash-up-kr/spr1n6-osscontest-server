package com.osscontest.server.search.application

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.search.config.SearchProperties
import com.osscontest.server.search.domain.SearchOptions
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
            .coerceIn(searchProperties.minTopK, searchProperties.maxTopK)
        val contextWindow = (request.contextWindow ?: searchProperties.defaultContextWindow)
            .coerceIn(searchProperties.minContextWindow, searchProperties.maxContextWindow)
        val efSearch = (request.efSearch ?: DEFAULT_EF_SEARCH)
            .coerceIn(searchProperties.minEfSearch, searchProperties.maxEfSearch)
            // efSearch가 topK보다 작으면 HNSW가 후보를 topK개만큼 못 채워 에러 없이 결과가
            // topK개보다 적게 반환된다(실측 확인). 항상 topK 이상을 보장한다.
            .coerceAtLeast(topK)

        val queryEmbedding = embeddingClient.embed(query)

        return searchChunkRepository.hybridSearch(
            tenantId = user.tenantId,
            userId = user.userId,
            queryText = query,
            queryEmbedding = queryEmbedding,
            options = SearchOptions(topK = topK, contextWindow = contextWindow, efSearch = efSearch),
        )
    }

    companion object {
        // 실제 데이터 규모에서 EXPLAIN ANALYZE로 검증한 뒤 변경 예정
        private const val DEFAULT_EF_SEARCH = 100
    }
}

data class SearchRequest(
    val query: String,
    val topK: Int? = null,
    val contextWindow: Int? = null,
    val efSearch: Int? = null,
)
