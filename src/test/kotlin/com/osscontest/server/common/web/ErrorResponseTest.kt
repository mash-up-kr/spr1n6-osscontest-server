package com.osscontest.server.common.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 스프링 MVC 가 정해 둔 상태 코드가 살아 있는지 본다.
 * Exception 만 잡으면 아래가 전부 500 이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorResponseTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `없는 경로는 404 다`() {
        mockMvc.get("/test/nope").andExpect { status { isNotFound() } }
    }

    @Test
    fun `지원하지 않는 메서드는 405 다`() {
        mockMvc.delete("/test/validate").andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.code") { value("METHOD_NOT_ALLOWED") }
            jsonPath("$.traceId") { exists() }
        }
    }

    @Test
    fun `읽을 수 없는 본문은 400 이다`() {
        mockMvc.post("/test/validate") {
            contentType = MediaType.APPLICATION_JSON
            content = "{ this is not json"
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `파라미터 제약을 어기면 400 이다`() {
        mockMvc.get("/test/param") { param("limit", "0") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
            jsonPath("$.message") { value(org.hamcrest.Matchers.containsString("limit")) }
            jsonPath("$.traceId") { exists() }
        }
    }

    @Test
    fun `지원하지 않는 Content-Type 은 415 다`() {
        mockMvc.post("/test/validate") {
            contentType = MediaType.TEXT_PLAIN
            content = "hello"
        }.andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.code") { value("UNSUPPORTED_MEDIA_TYPE") }
        }
    }
}
