package com.osscontest.server.config

import com.osscontest.server.search.domain.SearchOptions
import com.osscontest.server.search.infrastructure.SearchChunkRepository
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `search.datasource.url` 이 있으면 검색 쿼리가 기본 풀이 아니라 검색 풀로 나간다. */
@SpringBootTest(
    properties = [
        "search.datasource.url=\${spring.datasource.url}",
        "search.datasource.username=\${spring.datasource.username}",
        "search.datasource.password=\${spring.datasource.password}",
        "search.datasource.hikari.pool-name=search-test",
    ],
)
class SearchDataSourceRoutingTest {

    @Autowired
    private lateinit var dataSource: HikariDataSource

    @Autowired
    @Qualifier("searchDataSource")
    private lateinit var searchDataSource: HikariDataSource

    @Autowired
    @Qualifier("searchTransactionManager")
    private lateinit var searchTransactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var repository: SearchChunkRepository

    @Autowired
    private lateinit var context: ApplicationContext

    private val dummyEmbedding = List(1536) { if (it == 0) 1.0f else 0.0f }

    @Test
    fun `검색은 검색 풀과 그 풀의 트랜잭션 매니저를 쓴다`() {
        assertNotSame(dataSource, searchDataSource)
        assertSame(searchDataSource, (searchTransactionManager as DataSourceTransactionManager).dataSource)
        // HikariDataSource 는 첫 연결 요청 때 풀을 연다. 검색 전에는 아무도 이 풀을 쓰지 않았다.
        assertNull(searchDataSource.hikariPoolMXBean)

        repository.hybridSearch(
            tenantId = -1,
            userId = -1,
            // 형태소가 없는 질의라 키워드 단계(CorpusStatsCache)를 건너뛰고 리포지토리 쿼리만 남는다.
            queryText = "?",
            queryEmbedding = dummyEmbedding,
            options = SearchOptions(topK = 10, contextWindow = 0, rerank = false),
        )

        assertTrue(searchDataSource.hikariPoolMXBean.totalConnections > 0)
    }

    @Test
    fun `서버 상태 확인은 기본 연결만 본다`() {
        // 검색 풀까지 묶는 Boot 기본 contributor 대신, 기본 연결 하나만 보는 indicator 가 등록된다.
        assertTrue(!context.containsBean("dbHealthContributor"))
        assertEquals(listOf("dbHealthIndicator"), context.getBeanNamesForType(DataSourceHealthIndicator::class.java).toList())
    }
}
