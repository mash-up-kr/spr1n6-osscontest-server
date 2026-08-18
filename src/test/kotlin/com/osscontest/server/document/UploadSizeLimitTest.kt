package com.osscontest.server.document

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 크기 제한은 서블릿 컨테이너가 거는 것이라 MockMvc 로는 검증되지 않는다.
 * max-swallow-size 를 풀지 않으면 톰캣이 응답 전에 연결을 끊어 413 이 클라이언트에 닿지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadSizeLimitTest {

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `20MB 를 넘으면 413 이다`() {
        val body = LinkedMultiValueMap<String, Any>()
        body.add(
            "file",
            object : ByteArrayResource(ByteArray(21 * 1024 * 1024)) {
                override fun getFilename() = "big.pdf"
            },
        )

        val response = RestClient.create("http://localhost:$port")
            .post()
            .uri("/api/v1/documents")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .retrieve()
            .onStatus({ true }, { _, _ -> })
            .toEntity(String::class.java)

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), response.statusCode.value())
        assertTrue(response.body!!.contains("traceId"), "에러 응답 형식이어야 한다")
    }
}
