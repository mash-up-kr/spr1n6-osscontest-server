package com.osscontest.server.config

import com.zaxxer.hikari.SQLExceptionOverride.Override
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals

class OpenProxyExceptionOverrideTest {

    private val override = OpenProxyExceptionOverride()

    @Test
    fun `읽기 전용 서버에 쓴 연결은 버린다`() {
        assertEquals(Override.MUST_EVICT, override.adjudicate(SQLException("read-only", "25006")))
    }

    @Test
    fun `OpenProxy 가 붙을 서버를 찾지 못한 연결은 버린다`() {
        assertEquals(Override.MUST_EVICT, override.adjudicate(SQLException("AllServersDown", "58000")))
    }

    @Test
    fun `그 밖의 오류는 HikariCP 기본 판정에 맡긴다`() {
        assertEquals(Override.CONTINUE_EVICT, override.adjudicate(SQLException("unique violation", "23505")))
        assertEquals(Override.CONTINUE_EVICT, override.adjudicate(SQLException("no state")))
    }
}
