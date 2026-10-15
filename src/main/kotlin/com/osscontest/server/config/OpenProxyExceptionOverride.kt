/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.config

import com.zaxxer.hikari.SQLExceptionOverride
import java.sql.SQLException

/**
 * OpenProxy 뒤의 Primary 가 바뀐 뒤 남은 연결을 풀에서 버리게 한다.
 *
 * 두 오류 모두 HikariCP 기본 판정으로는 연결을 버리지 않아, 같은 연결이 계속 실패한다.
 * - 25006: Replica 로 내려간 옛 Primary 에 쓰기를 보냈다
 * - 58000: OpenProxy 가 붙을 서버를 찾지 못했다(AllServersDown)
 *
 * HikariCP 가 인자 없는 생성자로 만들므로 `exception-override-class-name` 으로 지정한다.
 */
class OpenProxyExceptionOverride : SQLExceptionOverride {

    override fun adjudicate(sqlException: SQLException): SQLExceptionOverride.Override =
        if (sqlException.sqlState in EVICT_SQL_STATES) {
            SQLExceptionOverride.Override.MUST_EVICT
        } else {
            SQLExceptionOverride.Override.CONTINUE_EVICT
        }

    private companion object {
        val EVICT_SQL_STATES = setOf("25006", "58000")
    }
}
