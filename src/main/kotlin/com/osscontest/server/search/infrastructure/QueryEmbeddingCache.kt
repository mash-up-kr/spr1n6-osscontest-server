package com.osscontest.server.search.infrastructure

import org.springframework.stereotype.Component
import java.util.Collections

/**
 * 질의 텍스트 → 임베딩 벡터 캐시. 같은 텍스트는 항상 같은 벡터를 내므로(모델·차원이
 * 고정이라 테넌트와 무관하게 재사용 가능) OpenAI 호출을 건너뛸 수 있다. 검색 지연시간의
 * 대부분이 임베딩 API 왕복이라(DB 쪽은 ms 단위) 반복 질의에서 가장 효과가 크다.
 *
 * LinkedHashMap을 접근 순서(access-order) 모드로 써서 LRU를 구현한다 — 최근 안 쓴
 * 항목부터 밀려나므로 자주 나오는 질의가 캐시에 남는다.
 */
@Component
class QueryEmbeddingCache {
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<Float>>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Float>>?): Boolean =
                size > MAX_ENTRIES
        },
    )

    fun get(query: String): List<Float>? = cache[query]

    fun put(query: String, embedding: List<Float>) {
        cache[query] = embedding
    }

    companion object {
        // 1,536차원 Float 하나당 항목 하나가 대략 6KB라, 1,000개를 들고 있어도 수 MB 수준이다.
        private const val MAX_ENTRIES = 1_000
    }
}
