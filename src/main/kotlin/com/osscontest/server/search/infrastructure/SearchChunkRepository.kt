package com.osscontest.server.search.infrastructure

import com.osscontest.server.search.domain.SearchResultItem
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/**
 * 검색 설계 문서 4.3절 "하이브리드 검색 쿼리 (RRF)"의 구현체.
 *
 * JPA/JPQL 로는 accessible_docs/RRF/LATERAL 문맥 확장을 표현할 수 없어 네이티브 SQL을 직접 쓴다.
 * `tenant_id`/`user_id`는 principal_id(VARCHAR) 비교와 tenant_id(BIGINT) 비교에 동시에 쓰이는데,
 * 같은 파라미터를 두 타입으로 바인딩할 수 없어 텍스트 비교용 파라미터를 별도로 둔다.
 */
@Repository
class SearchChunkRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    fun hybridSearch(
        tenantId: Long,
        userId: Long,
        queryText: String,
        queryEmbedding: List<Float>,
        topK: Int,
        contextWindow: Int,
    ): List<SearchResultItem> {
        val params = MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("tenantIdText", tenantId.toString())
            .addValue("userIdText", userId.toString())
            .addValue("queryText", queryText)
            .addValue("queryEmbedding", queryEmbedding.toPgVectorLiteral())
            .addValue("topK", topK)
            .addValue("contextWindow", contextWindow)

        return jdbcTemplate.query(HYBRID_SEARCH_SQL, params, ::mapRow)
    }

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): SearchResultItem =
        SearchResultItem(
            chunkId = rs.getLong("id"),
            documentId = rs.getLong("document_id"),
            title = rs.getString("title"),
            content = rs.getString("content"),
            contextBefore = rs.getStringArray("context_before"),
            contextAfter = rs.getStringArray("context_after"),
            score = rs.getDouble("rrf_score"),
            pageFrom = rs.getInt("page_from").takeUnless { rs.wasNull() },
            pageTo = rs.getInt("page_to").takeUnless { rs.wasNull() },
            sectionPath = rs.getString("section_path"),
        )

    private fun ResultSet.getStringArray(column: String): List<String> {
        val array = getArray(column) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (array.array as Array<String?>).filterNotNull()
    }

    companion object {
        // language=SQL
        private val HYBRID_SEARCH_SQL = """
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
            ),
            vector_search AS (
                SELECT dc.id,
                       ROW_NUMBER() OVER (ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)) AS rank_vec
                FROM document_chunk dc
                JOIN accessible_docs ad
                  ON ad.id = dc.document_id AND ad.searchable_version_id = dc.document_version_id
                WHERE dc.tenant_id = :tenantId
                ORDER BY dc.embedding <=> CAST(:queryEmbedding AS vector)
                LIMIT 50
            ),
            keyword_search AS (
                SELECT dc.id,
                       ROW_NUMBER() OVER (
                           ORDER BY ts_rank_cd(to_tsvector('simple', dc.content), plainto_tsquery('simple', :queryText)) DESC
                       ) AS rank_kw
                FROM document_chunk dc
                JOIN accessible_docs ad
                  ON ad.id = dc.document_id AND ad.searchable_version_id = dc.document_version_id
                WHERE dc.tenant_id = :tenantId
                  AND to_tsvector('simple', dc.content) @@ plainto_tsquery('simple', :queryText)
                ORDER BY ts_rank_cd(to_tsvector('simple', dc.content), plainto_tsquery('simple', :queryText)) DESC
                LIMIT 50
            ),
            ranked AS (
                SELECT
                    dc.id,
                    dc.document_id,
                    dc.document_version_id,
                    dc.chunk_no,
                    dc.content,
                    dc.page_from,
                    dc.page_to,
                    dc.section_path,
                    (COALESCE(1.0 / (60 + v.rank_vec), 0) + COALESCE(1.0 / (60 + k.rank_kw), 0)) AS rrf_score
                FROM document_chunk dc
                LEFT JOIN vector_search v ON v.id = dc.id
                LEFT JOIN keyword_search k ON k.id = dc.id
                WHERE v.id IS NOT NULL OR k.id IS NOT NULL
                ORDER BY rrf_score DESC
                LIMIT :topK
            )
            SELECT
                r.id,
                r.document_id,
                d.title,
                r.content,
                r.page_from,
                r.page_to,
                r.section_path,
                r.rrf_score,
                ctx_before.contents AS context_before,
                ctx_after.contents AS context_after
            FROM ranked r
            JOIN document d ON d.id = r.document_id
            LEFT JOIN LATERAL (
                SELECT array_agg(content ORDER BY chunk_no ASC) AS contents
                FROM document_chunk
                WHERE document_version_id = r.document_version_id
                  AND chunk_no BETWEEN r.chunk_no - :contextWindow AND r.chunk_no - 1
            ) ctx_before ON :contextWindow > 0
            LEFT JOIN LATERAL (
                SELECT array_agg(content ORDER BY chunk_no ASC) AS contents
                FROM document_chunk
                WHERE document_version_id = r.document_version_id
                  AND chunk_no BETWEEN r.chunk_no + 1 AND r.chunk_no + :contextWindow
            ) ctx_after ON :contextWindow > 0
            ORDER BY r.rrf_score DESC
        """.trimIndent()
    }
}
