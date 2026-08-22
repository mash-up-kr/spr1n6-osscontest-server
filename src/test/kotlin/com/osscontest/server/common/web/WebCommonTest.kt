package com.osscontest.server.common.web

import com.osscontest.server.tenant.domain.Tenant
import com.osscontest.server.user.domain.AppUser
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
}
