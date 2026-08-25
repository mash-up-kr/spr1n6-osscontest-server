package com.osscontest.server.search.domain

/**
 * RRF로 뽑은 후보를 질의와의 실제 적합도로 다시 정렬한다. 벡터 유사도·BM25는 질의와
 * 문서를 각각 따로 인코딩해 비교하는 근사치라, 후보군 안에서의 정밀한 순위까지는
 * 보장하지 못한다 — 이 재정렬이 그 간극을 좁힌다.
 */
interface Reranker {
    fun rerank(query: String, candidates: List<RerankCandidate>): List<Long>
}
