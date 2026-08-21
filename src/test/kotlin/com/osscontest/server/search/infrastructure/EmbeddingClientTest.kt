package com.osscontest.server.search.infrastructure

import com.openai.errors.OpenAIException
import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.search.config.SearchProperties
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.ai.embedding.EmbeddingModel
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmbeddingClientTest {

    private val embeddingModel = mock<EmbeddingModel>()
    private val client = EmbeddingClient(
        embeddingModel = embeddingModel,
        searchProperties = SearchProperties(embedding = SearchProperties.Embedding(dimensions = EMBEDDING_DIMENSIONS)),
    )

    @Test
    fun `EmbeddingModel이 반환한 벡터를 그대로 반환한다`() {
        val expected = FloatArray(EMBEDDING_DIMENSIONS) { 0.1f }
        whenever(embeddingModel.embed("위약금")).thenReturn(expected)

        val vector = client.embed("위약금")

        assertEquals(EMBEDDING_DIMENSIONS, vector.size)
        assertEquals(0.1f, vector.first())
        verify(embeddingModel).embed("위약금")
    }

    @Test
    fun `openai-java SDK가 재시도 끝에 예외를 던지면 공통 upstream 오류로 변환한다`() {
        whenever(embeddingModel.embed("위약금")).thenThrow(OpenAIException("rate limited"))

        val exception = assertFailsWith<BusinessException> {
            client.embed("위약금")
        }

        assertEquals(ErrorCode.UPSTREAM_ERROR, exception.errorCode)
        assertEquals(ErrorCode.UPSTREAM_ERROR.message, exception.message)
    }

    @Test
    fun `빈 벡터가 오면 upstream 오류를 반환한다`() {
        whenever(embeddingModel.embed("위약금")).thenReturn(FloatArray(0))

        val exception = assertFailsWith<BusinessException> {
            client.embed("위약금")
        }

        assertEquals(ErrorCode.UPSTREAM_ERROR, exception.errorCode)
    }

    @Test
    fun `응답 벡터가 설정된 차원과 다르면 upstream 오류를 반환한다`() {
        whenever(embeddingModel.embed("위약금")).thenReturn(FloatArray(2) { 0.1f })

        val exception = assertFailsWith<BusinessException> {
            client.embed("위약금")
        }

        assertEquals(ErrorCode.UPSTREAM_ERROR, exception.errorCode)
        assertEquals(ErrorCode.UPSTREAM_ERROR.message, exception.message)
    }

    companion object {
        private const val EMBEDDING_DIMENSIONS = 1_536
    }
}
