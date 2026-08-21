package com.osscontest.server.search.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 검색 설계 문서 8/9장 기준 기본값.
 * topK/contextWindow 상한 초과 시 거부가 아니라 clamp 한다
 * 문서에 "거부"로 명시된 바 없어 더 관대한 쪽으로 구현 시점에 결정
 */
@Component
@ConfigurationProperties(prefix = "search")
data class SearchProperties(
    var defaultTopK: Int = 10,
    var maxTopK: Int = 50,
    var maxContextWindow: Int = 5,
    var embedding: Embedding = Embedding(),
) {
    data class Embedding(
        var dimensions: Int = 1_536,
    )
}
