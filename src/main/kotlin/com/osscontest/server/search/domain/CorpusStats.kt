package com.osscontest.server.search.domain

import kotlin.math.ln

/**
 * BM25의 IDF·문서 길이 정규화에 필요한 코퍼스 통계.
 * documentFrequency에 없는 단어는 코퍼스에 없던 것으로 보고 df=0으로 취급한다
 * (희귀어일수록 IDF가 커지는 공식이라 이 경우도 자연스럽게 처리된다).
 */
data class CorpusStats(
    val documentFrequency: Map<String, Int>,
    val totalDocuments: Int,
    val avgDocLength: Double,
) {
    // Robertson-Spärck Jones IDF에 +1을 더한 변형(Lucene·Elasticsearch가 쓰는 것과 동일)으로,
    // 코퍼스 절반 이상에 등장하는 흔한 단어라도 IDF가 음수로 떨어지지 않는다.
    fun idf(term: String): Double {
        val df = documentFrequency[term] ?: 0
        return ln((totalDocuments - df + 0.5) / (df + 0.5) + 1)
    }

    companion object {
        val EMPTY = CorpusStats(documentFrequency = emptyMap(), totalDocuments = 0, avgDocLength = 1.0)
    }
}
