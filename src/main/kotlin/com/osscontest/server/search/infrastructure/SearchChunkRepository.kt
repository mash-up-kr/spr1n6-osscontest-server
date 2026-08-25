package com.osscontest.server.search.infrastructure

import com.osscontest.server.search.config.SearchProperties
import com.osscontest.server.search.domain.Bm25Scorer
import com.osscontest.server.search.domain.RerankCandidate
import com.osscontest.server.search.domain.Reranker
import com.osscontest.server.search.domain.SearchOptions
import com.osscontest.server.search.domain.SearchResultItem
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

/**
 * 검색 설계 문서 4.3절 "하이브리드 검색 쿼리 (RRF)"의 구현체.
 *
 * 벡터·키워드 후보 회수는 SQL에서, BM25 랭킹과 RRF 결합은 Kotlin에서 한다.
 * Postgres의 ts_rank_cd는 IDF가 없어 코퍼스에 흔한 단어를 못 눌러주는데, IDF
 * 계산 자체가 SQL 표현식으로는 다루기 번거로워 애플리케이션 레이어로 옮겼다.
 * `tenant_id`/`user_id`는 principal_id(VARCHAR) 비교와 tenant_id(BIGINT) 비교에
 * 동시에 쓰이는데, 같은 파라미터를 두 타입으로 바인딩할 수 없어 텍스트 비교용
 * 파라미터를 별도로 둔다.
 */
@Repository
class SearchChunkRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val noriTokenizer: NoriTokenizer,
    private val corpusStatsCache: CorpusStatsCache,
    private val reranker: Reranker,
    private val searchProperties: SearchProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun hybridSearch(
        tenantId: Long,
        userId: Long,
        queryText: String,
        queryEmbedding: List<Float>,
        options: SearchOptions,
    ): List<SearchResultItem> {
        // SET 문은 파라미터 바인딩을 지원하지 않아 문자열로 조립해야 하는데, EF_SEARCH가
        // 상수라 사용자 입력이 섞일 여지가 없다. SET LOCAL은 트랜잭션 스코프에서만 유효해
        // @Transactional이 반드시 있어야 하고, 없으면 이 설정이 조용히 무시되고 커넥션 풀
        // 재사용 시 다음 요청으로 값이 샐 수 있다.
        jdbcTemplate.jdbcOperations.execute("SET LOCAL hnsw.ef_search = $EF_SEARCH")

        val vectorRanks = vectorSearch(tenantId, userId, queryEmbedding)
        val keywordRanks = keywordSearch(tenantId, userId, queryText)

        val rrfScores = (vectorRanks.keys + keywordRanks.keys).associateWith { id ->
            rrfContribution(vectorRanks[id]) + rrfContribution(keywordRanks[id])
        }
        // 재정렬을 쓰면 RRF만으로는 topK 밖일 후보도 재정렬 대상에 들어올 수 있게 더 넓게 뽑는다.
        val fetchLimit = if (options.rerank) {
            maxOf(options.topK, searchProperties.rerank.candidatePoolSize)
        } else {
            options.topK
        }
        val topIds = rrfScores.entries.sortedByDescending { it.value }.take(fetchLimit).map { it.key }
        if (topIds.isEmpty()) return emptyList()

        val items = fetchDetails(topIds, options.contextWindow, rrfScores)
        val ordered = if (options.rerank) rerank(queryText, items) else items
        return ordered.take(options.topK)
    }

    // 리랭킹 실패(API 오류, 응답 파싱 실패 등)는 검색 자체를 실패시키지 않고 RRF 순서로 대체한다(fail-open).
    // 재정렬 결과가 후보 일부만 포함해도 나머지는 원래 RRF 순서로 뒤에 채운다.
    private fun rerank(queryText: String, items: List<SearchResultItem>): List<SearchResultItem> {
        val candidates = items.map { RerankCandidate(it.chunkId, it.content) }
        val rerankedIds = try {
            reranker.rerank(queryText, candidates)
        } catch (e: Exception) {
            log.warn("리랭킹 실패, RRF 순서로 대체합니다", e)
            return items
        }
        val byId = items.associateBy { it.chunkId }
        val covered = rerankedIds.toSet()
        val remaining = items.filter { it.chunkId !in covered }
        return rerankedIds.mapNotNull { byId[it] } + remaining
    }

    private fun rrfContribution(rank: Int?): Double = if (rank == null) 0.0 else 1.0 / (RRF_K + rank)

    // 벡터 유사도 순위. HNSW 인덱스를 타는 ORDER BY다. 결과 순서 자체가 순위라 별도 컬럼 없이
    // 리스트 인덱스로 순위를 매긴다.
    private fun vectorSearch(
        tenantId: Long,
        userId: Long,
        queryEmbedding: List<Float>,
    ): Map<Long, Int> {
        val params = accessParams(tenantId, userId)
            .addValue("queryEmbedding", queryEmbedding.toPgVectorLiteral())
        val ids = jdbcTemplate.query(VECTOR_SEARCH_SQL, params) { rs, _ -> rs.getLong("id") }
        return ids.withIndex().associate { (index, id) -> id to (index + 1) }
    }

    // 키워드 후보 회수는 "질의 토큰 중 하나라도 겹치는 청크"를 넓게(OR 매칭) 가져오기만 하고,
    // 실제 랭킹(IDF 포함 BM25)은 회수된 후보에 대해서만 애플리케이션에서 계산한다 — 코퍼스
    // 전체에 IDF를 매기는 건 이 캐시(CorpusStatsCache)가 별도로 맡는다.
    private fun keywordSearch(
        tenantId: Long,
        userId: Long,
        queryText: String,
    ): Map<Long, Int> {
        val queryTerms = noriTokenizer.tokenize(queryText)
        if (queryTerms.isEmpty()) return emptyMap()

        val stats = corpusStatsCache.current()
        // 코퍼스 대부분에 등장하는 흔한 단어까지 OR 매칭에 넣으면 회수 단계가 사실상 전수
        // 스캔이 돼 버려(느려질뿐더러 ts_rank_cd 사전 정렬 비용도 커진다) — 어차피 BM25에서
        // IDF로 거의 기여하지 못할 단어라 회수 단계에서는 제외한다. 채점은 원래 토큰 전체로
        // 한다(recallTerms는 SQL 후보 회수 범위를 좁히는 용도일 뿐이다).
        val recallTerms = queryTerms.filter { term ->
            stats.totalDocuments == 0 ||
                (stats.documentFrequency[term] ?: 0).toDouble() / stats.totalDocuments <= COMMON_TERM_DF_RATIO
        }.ifEmpty { queryTerms } // 전부 흔한 단어라 다 걸러졌으면 0건 회수보다는 원래 토큰으로 되돌아간다.

        val params = accessParams(tenantId, userId)
            .addValue("orQuery", recallTerms.joinToString(" | "))
        val candidates = jdbcTemplate.query(KEYWORD_CANDIDATES_SQL, params) { rs, _ ->
            rs.getLong("id") to rs.getString("content_tokens")
        }

        // OR 회수는 질의 토큰 중 하나만 겹쳐도 후보로 들어와서, 우연히 흔치 않은 단어 하나만
        // 겹치는 관련 없는 후보가 섞일 수 있다. minimum_should_match처럼 질의 토큰의 일정
        // 비율 이상이 실제로 겹치는 후보만 BM25 채점 대상으로 남긴다.
        val minMatches = (queryTerms.size * MIN_MATCH_RATIO).toInt().coerceAtLeast(1)
        return candidates
            .filter { (_, contentTokens) ->
                val tokens = contentTokens.split(" ").toSet()
                queryTerms.count { it in tokens } >= minMatches
            }
            .map { (id, contentTokens) -> id to Bm25Scorer.score(queryTerms, contentTokens.split(" "), stats) }
            .sortedByDescending { it.second }
            .take(RRF_CANDIDATE_LIMIT)
            .withIndex()
            .associate { (index, scored) -> scored.first to (index + 1) }
    }

    private fun accessParams(tenantId: Long, userId: Long): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("tenantIdText", tenantId.toString())
            .addValue("userIdText", userId.toString())

    private fun fetchDetails(
        ids: List<Long>,
        contextWindow: Int,
        rrfScores: Map<Long, Double>,
    ): List<SearchResultItem> {
        val params = MapSqlParameterSource()
            .addValue("ids", ids)
            .addValue("contextWindow", contextWindow)
        val rows = jdbcTemplate.query(DETAIL_SQL, params) { rs, rowNum -> mapRow(rs, rowNum, rrfScores) }
        // ids는 IN절 순서를 보장하지 않으므로 Kotlin에서 이미 계산해 둔 RRF 점수로 다시 정렬한다.
        return rows.sortedByDescending { it.score }
    }

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int, rrfScores: Map<Long, Double>): SearchResultItem {
        val id = rs.getLong("id")
        return SearchResultItem(
            chunkId = id,
            documentId = rs.getLong("document_id"),
            title = rs.getString("title"),
            content = rs.getString("content"),
            contextBefore = rs.getStringArray("context_before"),
            contextAfter = rs.getStringArray("context_after"),
            score = rrfScores.getValue(id),
            pageFrom = rs.getInt("page_from").takeUnless { rs.wasNull() },
            pageTo = rs.getInt("page_to").takeUnless { rs.wasNull() },
            sectionPath = rs.getString("section_path"),
        )
    }

    private fun ResultSet.getStringArray(column: String): List<String> {
        val array = getArray(column) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (array.array as Array<String?>).filterNotNull()
    }

    companion object {
        // HNSW 탐색 폭. 요청 파라미터로 열어뒀던 적이 있었는데, 값별 트레이드오프를 우리
        // 데이터로 제대로 스윕해보지 않은 채였다 — 그때 쓰던 기본값을 상수로 고정했다.
        // ANN이 exact와 얼마나 겹치는지는 40에서 평균 84%, 200에서도 96%에 그친다(recall_benchmark.py).
        private const val EF_SEARCH = 100

        // 벡터/키워드 각 순위 목록에서 RRF 후보로 넘길 상위 건수. 늘리면 recall은 오르지만
        // 두 CTE가 그만큼 더 넓게 정렬해야 해 지연이 늘어난다.
        private const val RRF_CANDIDATE_LIMIT = 50

        // 키워드 후보 회수(OR 매칭) 단계의 상한. BM25 랭킹 전 단계라 RRF_CANDIDATE_LIMIT보다
        // 넉넉하게 잡아, 실제로 랭킹이 높을 후보가 회수 단계에서부터 잘리지 않게 한다.
        private const val KEYWORD_RECALL_LIMIT = 300

        // 질의 토큰 중 코퍼스의 이 비율 이상 문서에 등장하는 단어는 OR 회수 쿼리에서 뺀다.
        // BM25 IDF상 기여가 작아 채점 결과에 미치는 영향은 미미한데, 회수 단계에서 매칭
        // 건수만 폭증시켜(전수 스캔에 가까워짐) 지연을 키운다.
        private const val COMMON_TERM_DF_RATIO = 0.15

        // Elasticsearch의 minimum_should_match와 같은 취지. OR 회수 후보 중 질의 토큰의
        // 이 비율 미만으로 겹치는 건 BM25 채점 전에 걸러 우연한 단어 하나 겹침으로 섞여
        // 들어온 노이즈 후보를 줄인다.
        private const val MIN_MATCH_RATIO = 0.5

        // RRF 점수식의 순위 완충 상수. Cormack et al.(2009)이 실험적으로 검증한 관행값으로,
        // 값이 작을수록 1등과 2등의 점수 차이가 커지고 클수록 순위 차이가 완만해진다.
        private const val RRF_K = 60

        // 테넌트·권한 조건을 만족하고 현재 검색 대상 버전인 문서만 남긴다.
        // 벡터·키워드 후보 쿼리가 각자 실행되는 별도 SELECT라 이 CTE를 양쪽에 그대로 반복한다.
        private const val ACCESSIBLE_DOCS_CTE = """
            WITH accessible_docs AS (
                SELECT d.id, d.searchable_version_id
                FROM document d
                WHERE d.tenant_id = :tenantId
                  AND d.deleted_at IS NULL
                  AND EXISTS (
                      SELECT 1
                      FROM document_access_scope das
                      WHERE das.document_id = d.id
                        AND das.tenant_id = :tenantId
                        AND das.permission IN ('READ', 'WRITE', 'ADMIN')
                        AND (
                             (das.principal_type = 'USER'   AND das.principal_id = :userIdText)
                          OR (das.principal_type = 'TENANT' AND das.principal_id = :tenantIdText)
                        )
                  )
            )
        """

        private val VECTOR_SEARCH_SQL = """
            $ACCESSIBLE_DOCS_CTE
            SELECT dc.id
            FROM document_chunk dc
            JOIN accessible_docs ad
              ON ad.id = dc.document_id AND ad.searchable_version_id = dc.document_version_id
            WHERE dc.tenant_id = :tenantId
            ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
            LIMIT $RRF_CANDIDATE_LIMIT
        """.trimIndent()

        // to_tsvector('simple', dc.content_tokens) 표현식이 idx_document_chunk_content_tokens_tsv
        // GIN 인덱스 정의와 문법적으로 정확히 일치해야 인덱스를 탄다.
        // 원문(content)이 아니라 Worker가 적재 시점에 Nori로 형태소 분석해 둔 content_tokens를
        // 쓴다 — 질의 쪽도 같은 형태소 분석기(NoriTokenizer)로 토큰화해야 서로 맞아떨어진다.
        // OR(|) 매칭이라 질의 토큰 중 하나라도 겹치면 후보로 들어오는데, 흔한 단어가 섞이면
        // 매칭 건수가 KEYWORD_RECALL_LIMIT을 훨씬 넘을 수 있다. ORDER BY 없이 LIMIT만 걸면
        // Postgres가 임의 순서로 잘라내 실제로 BM25 랭킹이 높을 후보가 애초에 회수 단계에서
        // 빠질 수 있어, IDF는 없어도 최소한의 사전 필터로 ts_rank_cd 순으로 정렬해 자른다.
        // 최종 순서는 여전히 애플리케이션의 BM25가 다시 매긴다.
        private val KEYWORD_CANDIDATES_SQL = """
            $ACCESSIBLE_DOCS_CTE
            SELECT dc.id, dc.content_tokens
            FROM document_chunk dc
            JOIN accessible_docs ad
              ON ad.id = dc.document_id AND ad.searchable_version_id = dc.document_version_id
            WHERE dc.tenant_id = :tenantId
              AND to_tsvector('simple', dc.content_tokens) @@ to_tsquery('simple', :orQuery)
            ORDER BY ts_rank_cd(to_tsvector('simple', dc.content_tokens), to_tsquery('simple', :orQuery)) DESC
            LIMIT $KEYWORD_RECALL_LIMIT
        """.trimIndent()

        // topK 결과에 문서 제목과 앞뒤 문맥을 붙여 반환한다. RRF 점수는 이미 Kotlin에서 계산해
        // 뒀으므로 여기서는 순서를 보장하지 않는 IN절로 대상 행만 가져온다.
        private val DETAIL_SQL = """
            SELECT
                dc.id,
                dc.document_id,
                d.title,
                dc.content,
                dc.page_from,
                dc.page_to,
                dc.section_path,
                ctx_before.contents AS context_before,
                ctx_after.contents AS context_after
            FROM document_chunk dc
            JOIN document d ON d.id = dc.document_id
            LEFT JOIN LATERAL (
                SELECT array_agg(content ORDER BY chunk_no ASC) AS contents
                FROM document_chunk
                WHERE document_version_id = dc.document_version_id
                  AND chunk_no BETWEEN dc.chunk_no - :contextWindow AND dc.chunk_no - 1
            ) ctx_before ON :contextWindow > 0
            LEFT JOIN LATERAL (
                SELECT array_agg(content ORDER BY chunk_no ASC) AS contents
                FROM document_chunk
                WHERE document_version_id = dc.document_version_id
                  AND chunk_no BETWEEN dc.chunk_no + 1 AND dc.chunk_no + :contextWindow
            ) ctx_after ON :contextWindow > 0
            WHERE dc.id IN (:ids)
        """.trimIndent()
    }
}
