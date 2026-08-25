package com.osscontest.server.search.infrastructure

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryEmbeddingCacheTest {

    @Test
    fun `저장한 값을 그대로 돌려준다`() {
        val cache = QueryEmbeddingCache()

        cache.put("위약금", listOf(0.1f, 0.2f))

        assertEquals(listOf(0.1f, 0.2f), cache.get("위약금"))
    }

    @Test
    fun `저장하지 않은 질의는 null이다`() {
        val cache = QueryEmbeddingCache()

        assertNull(cache.get("없는 질의"))
    }

    @Test
    fun `용량을 넘으면 가장 오래 안 쓴 항목부터 밀려난다`() {
        val cache = QueryEmbeddingCache()
        repeat(MAX_ENTRIES) { i -> cache.put("query-$i", listOf(i.toFloat())) }
        // query-0을 다시 조회해 "최근 사용"으로 갱신한다 — LRU라 이제 query-1이 가장 오래됐다.
        cache.get("query-0")

        cache.put("query-overflow", listOf(9999f))

        assertNull(cache.get("query-1"), "가장 오래 안 쓰인 항목이 밀려나야 한다")
        assertEquals(listOf(0f), cache.get("query-0"), "최근 조회한 항목은 남아 있어야 한다")
        assertEquals(listOf(9999f), cache.get("query-overflow"))
    }

    private companion object {
        const val MAX_ENTRIES = 1_000
    }
}
