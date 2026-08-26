package com.osscontest.server.search.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 검색 설계 문서 8/9장 기준 기본값.
 * topK/contextWindow 모두 상한(또는 하한) 초과 시 거부가 아니라 clamp 한다
 */
@Component
@ConfigurationProperties(prefix = "search")
data class SearchProperties(
    var defaultTopK: Int = 10,
    var minTopK: Int = 1,
    var maxTopK: Int = 50,
    var defaultContextWindow: Int = 0,
    var minContextWindow: Int = 0,
    var maxContextWindow: Int = 5,
    var embedding: Embedding = Embedding(),
    var rerank: Rerank = Rerank(),
) {
    data class Embedding(
        var dimensions: Int = 1_536,
    )

    data class Rerank(
        // RRF 상위 몇 건까지 재정렬 대상으로 삼을지. topK보다 넉넉해야 재정렬이
        // RRF 순위 밖의 후보도 끌어올릴 여지가 있다. on/off 자체는 요청 파라미터(rerank)로
        // 받되, 요청에 값이 없으면 이 기본값을 쓴다.
        var candidatePoolSize: Int = 30,
        // 합성 QA 평가(docs/SEARCH.md 5절)에서 rerank 가 Recall@10·MRR 을 일관되게
        // 끌어올려 기본값을 true 로 둔다. 리랭킹이 실패하면 fail-open 으로 RRF 순서로
        // 대체되므로(SearchChunkRepository.rerank()), COHERE_API_KEY 가 없어도 검색 자체는
        // 막히지 않는다.
        var defaultEnabled: Boolean = true,
        var cohere: Cohere = Cohere(),
    ) {
        data class Cohere(
            var apiKey: String = "",
            var model: String = "rerank-v3.5",
        )
    }
}
