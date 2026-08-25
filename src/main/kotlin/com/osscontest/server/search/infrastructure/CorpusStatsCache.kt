package com.osscontest.server.search.infrastructure

import com.osscontest.server.search.domain.CorpusStats
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * BM25 IDF·평균 문서 길이는 매 질의마다 전체 코퍼스를 스캔해 계산하기엔 비싸서
 * 캐시해 두고 일정 주기로만 다시 계산한다. 스케줄러 대신 조회 시점에 만료 여부를
 * 검사해 갱신하는 방식이라, 인덱싱 배치와 별도로 맞춰야 할 장치가 없다.
 */
@Component
class CorpusStatsCache(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    @Volatile
    private var cached: CorpusStats = CorpusStats.EMPTY

    @Volatile
    private var cachedAt: Instant = Instant.EPOCH

    fun current(): CorpusStats {
        val snapshot = cached
        if (Duration.between(cachedAt, Instant.now()) < REFRESH_INTERVAL && cachedAt != Instant.EPOCH) {
            return snapshot
        }
        return refresh()
    }

    @Synchronized
    private fun refresh(): CorpusStats {
        // 락 획득 대기 중 다른 스레드가 이미 갱신했으면 다시 스캔하지 않는다.
        if (Duration.between(cachedAt, Instant.now()) < REFRESH_INTERVAL && cachedAt != Instant.EPOCH) {
            return cached
        }

        val documentFrequency = jdbcTemplate.query(DOCUMENT_FREQUENCY_SQL, emptyMap<String, Any>()) { rs, _ ->
            rs.getString("word") to rs.getInt("ndoc")
        }.toMap()

        // count(*)는 GROUP BY 없는 집계라 대상이 0건이어도 항상 행 하나를 반환한다 —
        // 그 경우 avg_doc_length는 NULL이고 getDouble()은 0.0으로 읽히는데, 아래
        // avgDocLength 보정에서 그 경우를 걸러낸다.
        val (totalDocuments, avgDocLength) = jdbcTemplate.queryForObject(
            CORPUS_SIZE_SQL,
            emptyMap<String, Any>(),
        ) { rs, _ ->
            rs.getInt("total_documents") to rs.getDouble("avg_doc_length")
        }

        val stats = CorpusStats(
            documentFrequency = documentFrequency,
            totalDocuments = totalDocuments,
            avgDocLength = avgDocLength.takeIf { it > 0 } ?: 1.0,
        )
        cached = stats
        cachedAt = Instant.now()
        return stats
    }

    companion object {
        private val REFRESH_INTERVAL = Duration.ofMinutes(10)

        // ts_stat은 SQL 문자열을 인자로 받아 그 결과 tsvector 컬럼의 (단어, 등장 문서 수)를
        // 직접 집계해 준다 — 우리가 별도 통계 테이블을 만들고 유지할 필요가 없다.
        private const val DOCUMENT_FREQUENCY_SQL = """
            SELECT word, ndoc
            FROM ts_stat(
                ${"$"}${"$"}SELECT to_tsvector('simple', content_tokens)
                    FROM document_chunk
                    WHERE content_tokens IS NOT NULL${"$"}${"$"}
            )
        """

        private const val CORPUS_SIZE_SQL = """
            SELECT
                count(*) AS total_documents,
                avg(cardinality(string_to_array(content_tokens, ' '))) AS avg_doc_length
            FROM document_chunk
            WHERE content_tokens IS NOT NULL
        """
    }
}
