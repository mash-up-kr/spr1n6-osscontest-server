package com.osscontest.server.search.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Bm25ScorerTest {

    @Test
    fun `코퍼스 전체에 흔한 단어보다 희귀한 단어의 기여가 크다`() {
        // "국회"는 10개 문서 중 9개에 등장(흔함), "위약금"은 1개에만 등장(희귀).
        val stats = CorpusStats(
            documentFrequency = mapOf("국회" to 9, "위약금" to 1),
            totalDocuments = 10,
            avgDocLength = 5.0,
        )

        val commonTermScore = Bm25Scorer.score(listOf("국회"), listOf("국회", "예산"), stats)
        val rareTermScore = Bm25Scorer.score(listOf("위약금"), listOf("위약금", "계약금"), stats)

        assertTrue(rareTermScore > commonTermScore, "희귀어 점수($rareTermScore)가 흔한어 점수($commonTermScore)보다 커야 한다")
    }

    @Test
    fun `쿼리 단어가 문서에 없으면 기여가 0이다`() {
        val stats = CorpusStats(documentFrequency = mapOf("국회" to 1), totalDocuments = 10, avgDocLength = 5.0)

        val score = Bm25Scorer.score(listOf("예산"), listOf("국회", "정기"), stats)

        assertEquals(0.0, score)
    }

    @Test
    fun `빈 쿼리나 빈 문서는 0점이다`() {
        val stats = CorpusStats(documentFrequency = mapOf("국회" to 1), totalDocuments = 10, avgDocLength = 5.0)

        assertEquals(0.0, Bm25Scorer.score(emptyList(), listOf("국회"), stats))
        assertEquals(0.0, Bm25Scorer.score(listOf("국회"), emptyList(), stats))
    }

    @Test
    fun `같은 단어라도 문서가 평균보다 짧으면 점수가 더 높다`() {
        val stats = CorpusStats(documentFrequency = mapOf("국회" to 5), totalDocuments = 10, avgDocLength = 10.0)

        val shortDocScore = Bm25Scorer.score(listOf("국회"), listOf("국회", "예산"), stats)
        val longDocPadding = listOf("국회") + List(18) { "패딩" }
        val longDocScore = Bm25Scorer.score(listOf("국회"), longDocPadding, stats)

        assertTrue(shortDocScore > longDocScore, "짧은 문서 점수($shortDocScore)가 긴 문서 점수($longDocScore)보다 커야 한다")
    }
}
