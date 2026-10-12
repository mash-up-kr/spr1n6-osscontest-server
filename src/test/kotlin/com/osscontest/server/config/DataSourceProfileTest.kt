package com.osscontest.server.config

import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
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
        assertEquals(8, dev("search.datasource.hikari.maximum-pool-size"))
    }

    @Test
    fun `local 프로필은 일반 PostgreSQL 에 붙으므로 SET SERVER ROLE 을 보내지 않는다`() {
        val local = load("application-local.yml")

        assertNull(local("spring.datasource.hikari.connection-init-sql"))
        assertNull(local("search.datasource.url"))
    }
}
