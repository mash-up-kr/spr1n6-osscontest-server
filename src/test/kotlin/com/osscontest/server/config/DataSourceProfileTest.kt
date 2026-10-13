package com.osscontest.server.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.SQLExceptionOverride.Override
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataSourceProfileTest {

    private fun load(file: String): (String) -> Any? {
        val sources = YamlPropertySourceLoader().load(file, ClassPathResource(file))
        return { key -> sources.firstNotNullOfOrNull { it.getProperty(key) } }
    }

    @Test
    fun `dev 프로필은 연결마다 SET SERVER ROLE 한 문장만 보낸다`() {
        val dev = load("application-dev.yml")

        assertEquals("SET SERVER ROLE TO 'default'", dev("spring.datasource.hikari.connection-init-sql"))
        assertEquals(-1, dev("spring.datasource.hikari.initialization-fail-timeout"))
    }

    @Test
    fun `dev 프로필은 검색을 aidocs_ro 풀로 따로 연결한다`() {
        val dev = load("application-dev.yml")

        assertTrue((dev("search.datasource.url") as String).contains("/\${DB_SEARCH_NAME:aidocs_ro}?"))
        assertEquals("SET SERVER ROLE TO 'default'", dev("search.datasource.hikari.connection-init-sql"))
        assertEquals(-1, dev("search.datasource.hikari.initialization-fail-timeout"))
        assertEquals(8, dev("search.datasource.hikari.maximum-pool-size"))
    }

    @Test
    fun `dev 프로필은 두 연결 모두 OpenProxy 가 요구하는 JDBC 파라미터를 붙인다`() {
        val dev = load("application-dev.yml")

        DEV_DATASOURCE_PREFIXES.forEach { prefix ->
            val parameters = queryParameters(dev("$prefix.url") as String)

            // 9.0 이상이어야 드라이버가 extra_float_digits 를 접속 파라미터로 넘긴다. 낮으면 접속 직후 SET 쿼리를
            // 따로 보내고, OpenProxy 는 SET SERVER ROLE 보다 먼저 온 그 쿼리로 붙을 DB 를 정한다.
            val assumedMajorVersion = parameters["assumeMinServerVersion"]?.substringBefore('.')?.toIntOrNull() ?: 0
            assertTrue(assumedMajorVersion >= 9, "$prefix: $parameters")
            // OpenProxy 는 simple 프로토콜로 온 SET SERVER ROLE 만 가로챈다. 기본값 extended 면 DB 까지 넘어가 문법 오류가 난다.
            assertTrue(parameters["preferQueryMode"] in setOf("extendedForPrepared", "simple"), "$prefix: $parameters")
        }
    }

    @Test
    fun `dev 프로필은 두 연결 모두 OpenProxy 오류를 받은 연결을 풀에서 버린다`() {
        val dev = load("application-dev.yml")

        DEV_DATASOURCE_PREFIXES.forEach { prefix ->
            val className = assertNotNull(dev("$prefix.hikari.exception-override-class-name") as String?, prefix)
            // dev 기동 때 Hikari 가 하는 것처럼 클래스 이름으로 만든다. 클래스를 옮기거나 이름을 바꿔도 yml 문자열은
            // 따라 바뀌지 않고, local 프로필에는 이 설정이 없어 다른 테스트로는 잡히지 않는다.
            val override = assertDoesNotThrow(prefix) {
                HikariConfig().apply { exceptionOverrideClassName = className }.exceptionOverride
            }

            assertEquals(Override.MUST_EVICT, override.adjudicate(SQLException("read-only", "25006")), prefix)
            assertEquals(Override.MUST_EVICT, override.adjudicate(SQLException("AllServersDown", "58000")), prefix)
        }
    }

    @Test
    fun `dev 기본 연결의 풀 크기는 OpenProxy aidocs_session 풀 크기를 넘지 않는다`() {
        val dev = load("application-dev.yml")

        val poolSize = dev("spring.datasource.hikari.maximum-pool-size") as Int
        assertTrue(poolSize <= AIDOCS_SESSION_POOL_SIZE, "maximum-pool-size=$poolSize")
    }

    @Test
    fun `local 프로필은 일반 PostgreSQL 에 붙으므로 SET SERVER ROLE 을 보내지 않는다`() {
        val local = load("application-local.yml")

        assertNull(local("spring.datasource.hikari.connection-init-sql"))
        assertNull(local("search.datasource.url"))
    }

    // 값에 든 `${...}` 자리표시자는 풀지 않고 그대로 둔다.
    private fun queryParameters(url: String): Map<String, String> =
        url.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }

    private companion object {
        val DEV_DATASOURCE_PREFIXES = listOf("spring.datasource", "search.datasource")

        // DB 클러스터 OpenProxy 설정(openproxy.toml)의 aidocs_session 풀 크기다. 그쪽을 바꾸면 이 값도 함께 고친다.
        // 이 수를 넘긴 연결은 10초 기다린 뒤 58000(AllServersDown)으로 실패한다.
        const val AIDOCS_SESSION_POOL_SIZE = 14
    }
}
