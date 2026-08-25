package com.osscontest.server.search.domain

/**
 * Okapi BM25. ts_rank_cd는 IDF가 없어 코퍼스에 흔한 단어를 못 눌러주는 문제가 있어,
 * IDF를 포함한 랭킹을 애플리케이션 레이어에서 직접 계산한다.
 */
object Bm25Scorer {
    // Okapi BM25 관행값. k1은 TF 포화 속도(클수록 반복 등장에 더 관대), b는 문서 길이
    // 정규화 강도(0이면 길이를 무시, 1이면 완전히 보정)를 조절한다.
    private const val K1 = 1.2
    private const val B = 0.75

    fun score(
        queryTerms: List<String>,
        docTokens: List<String>,
        stats: CorpusStats,
    ): Double {
        if (queryTerms.isEmpty() || docTokens.isEmpty()) return 0.0

        val docLength = docTokens.size
        val termFrequency = docTokens.groupingBy { it }.eachCount()
        val lengthNorm = 1 - B + B * (docLength / stats.avgDocLength)

        return queryTerms.sumOf { term ->
            val tf = termFrequency[term] ?: return@sumOf 0.0
            val idf = stats.idf(term)
            idf * (tf * (K1 + 1)) / (tf + K1 * lengthNorm)
        }
    }
}
