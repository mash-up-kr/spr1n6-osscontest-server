package com.osscontest.server.search.infrastructure

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.search.config.SearchProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmbeddingClientTest {

    private lateinit var server: MockRestServiceServer
    private lateinit var client: EmbeddingClient
    private lateinit var delays: MutableList<Long>

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl(BASE_URL)
        server = MockRestServiceServer.bindTo(builder).build()
        delays = mutableListOf()
        client = EmbeddingClient(
            embeddingRestClient = builder.build(),
            searchProperties = properties(),
            retrySleeper = RetrySleeper { delays.add(it) },
        )
    }

    @Test
    fun `OpenAI 형식으로 요청하고 첫 번째 임베딩을 반환한다`() {
        server.expect(requestTo("$BASE_URL/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer $API_KEY"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(
                content().json(
                    """
                    {
                      "input": "위약금",
                      "model": "text-embedding-3-small",
                      "dimensions": 1536
                    }
                    """.trimIndent(),
                ),
            )
            .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON))

        val vector = client.embed("위약금")

        assertEquals(EMBEDDING_DIMENSIONS, vector.size)
        assertEquals(0.1f, vector.first())
        assertEquals(emptyList(), delays)
        server.verify()
    }

    @Test
    fun `일시 오류는 최대 5회 호출하며 선형 백오프한다`() {
        repeat(4) {
            server.expect(requestTo("$BASE_URL/embeddings"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        }
        server.expect(requestTo("$BASE_URL/embeddings"))
            .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON))

        val vector = client.embed("위약금")

        assertEquals(EMBEDDING_DIMENSIONS, vector.size)
        assertEquals(listOf(200L, 400L, 600L, 800L), delays)
        server.verify()
    }

    @Test
    fun `5회 모두 실패하면 공통 upstream 오류를 반환한다`() {
        repeat(5) {
            server.expect(requestTo("$BASE_URL/embeddings"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        }

        val exception = assertFailsWith<BusinessException> {
            client.embed("위약금")
        }

        assertEquals(ErrorCode.UPSTREAM_ERROR, exception.errorCode)
        assertEquals(ErrorCode.UPSTREAM_ERROR.message, exception.message)
        assertEquals(listOf(200L, 400L, 600L, 800L), delays)
        server.verify()
    }

    @Test
    fun `재시도해도 해결되지 않는 요청 오류는 즉시 실패한다`() {
        server.expect(requestTo("$BASE_URL/embeddings"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST))

        val exception = assertFailsWith<BusinessException> {
            client.embed("위약금")
        }

        assertEquals(ErrorCode.UPSTREAM_ERROR, exception.errorCode)
        assertEquals(emptyList(), delays)
        server.verify()
    }

    @Test
    fun `응답 벡터가 1536차원이 아니면 upstream 오류를 반환한다`() {
        server.expect(requestTo("$BASE_URL/embeddings"))
            .andRespond(
                withSuccess(
                    """{"data":[{"embedding":[0.1,0.2],"index":0}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val exception = assertFailsWith<BusinessException> {
            client.embed("위약금")
        }

        assertEquals(ErrorCode.UPSTREAM_ERROR, exception.errorCode)
        assertEquals(ErrorCode.UPSTREAM_ERROR.message, exception.message)
        server.verify()
    }

    private fun properties() = SearchProperties(
        embedding = SearchProperties.Embedding(
            baseUrl = BASE_URL,
            apiKey = API_KEY,
            model = "text-embedding-3-small",
            dimensions = EMBEDDING_DIMENSIONS,
            retryBackoffMs = 200,
        ),
    )

    private fun successResponse(): String {
        val embedding = List(EMBEDDING_DIMENSIONS) { "0.1" }.joinToString(",")
        return """{"data":[{"embedding":[$embedding],"index":0}]}"""
    }

    companion object {
        private const val BASE_URL = "https://api.openai.com/v1"
        private const val API_KEY = "test-api-key"
        private const val EMBEDDING_DIMENSIONS = 1_536
    }
}
