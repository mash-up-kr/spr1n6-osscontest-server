/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.common.web

import com.osscontest.server.tenant.domain.Tenant
import com.osscontest.server.user.domain.AppUser
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/** 인증 컨텍스트 주입과 에러 응답 규약을 확인한다. 도메인 로직과 섞이지 않도록 시험용 컨트롤러를 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WebCommonTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var em: EntityManager

    @Test
    fun `X-User-Id 가 없으면 401 이다`() {
        mockMvc.get("/test/auth")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHENTICATED") }
                jsonPath("$.traceId") { exists() }
            }
    }

    @Test
    fun `존재하지 않는 유저면 401 이다`() {
        mockMvc.get("/test/auth") { header(AuthContextArgumentResolver.USER_ID_HEADER, "999999") }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHENTICATED") }
            }
    }

    @Test
    fun `유저의 테넌트를 인증 컨텍스트로 넣어 준다`() {
        val tenant = Tenant(name = "mashup").also { em.persist(it) }
        val user = AppUser(tenant = tenant, email = "a@example.com", name = "장민서")
            .also { em.persist(it) }
        em.flush()

        mockMvc.get("/test/auth") {
            header(AuthContextArgumentResolver.USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.userId") { value(user.id!!) }
            jsonPath("$.tenantId") { value(tenant.id!!) }
        }
    }

    @Test
    fun `검증에 실패하면 400 과 실패한 필드를 알려 준다`() {
        mockMvc.post("/test/validate") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title": ""}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
            jsonPath("$.message") { value(org.hamcrest.Matchers.containsString("title")) }
            jsonPath("$.traceId") { exists() }
        }
    }

    @Test
    fun `DB 장애 전환 중 오류는 503 과 Retry-After 를 준다`() {
        listOf("58000", "25006", "57P01", "08006").forEach { sqlState ->
            mockMvc.get("/test/db-error") { param("sqlState", sqlState) }
                .andExpect {
                    status { isServiceUnavailable() }
                    header { string(HttpHeaders.RETRY_AFTER, "5") }
                    jsonPath("$.code") { value("DB_UNAVAILABLE") }
                    jsonPath("$.traceId") { exists() }
                }
        }
    }

    @Test
    fun `연결 대기 시간을 넘기면 503 이다`() {
        mockMvc.get("/test/db-connection-timeout")
            .andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.code") { value("DB_UNAVAILABLE") }
            }
    }

    @Test
    fun `장애 전환과 무관한 DB 오류는 500 이다`() {
        listOf("23505", "08P01").forEach { sqlState ->
            mockMvc.get("/test/db-error") { param("sqlState", sqlState) }
                .andExpect {
                    status { isInternalServerError() }
                    header { doesNotExist(HttpHeaders.RETRY_AFTER) }
                    jsonPath("$.code") { value("INTERNAL_ERROR") }
                }
        }
    }

    @Test
    fun `다른 출처의 웹이 Retry-After 를 읽을 수 있게 노출한다`() {
        mockMvc.get("/api/v1/documents") { header(HttpHeaders.ORIGIN, "http://localhost:5173") }
            .andExpect {
                header {
                    string(
                        HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        org.hamcrest.Matchers.containsString(HttpHeaders.RETRY_AFTER),
                    )
                }
            }
    }
}
