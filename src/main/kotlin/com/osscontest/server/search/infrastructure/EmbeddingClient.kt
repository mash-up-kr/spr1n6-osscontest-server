package com.osscontest.server.search.infrastructure

import com.openai.errors.OpenAIException
import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.search.config.SearchProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Component

/**
 * 검색 질의를 OpenAI Embeddings API로 변환한다.
 * EmbeddingModel·모델·차원을 사용해야 저장된 document_chunk.embedding과 의미 있는 유사도 비교가 가능하다.
 * 재시도·타임아웃은 openai-java SDK가 spring.ai.openai.max-retries timeout 기본값을 따른다.
 */
@Component
class EmbeddingClient(
    private val embeddingModel: EmbeddingModel,
    private val searchProperties: SearchProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun embed(text: String): List<Float> {
        val vector = try {
            embeddingModel.embed(text)
        } catch (ex: OpenAIException) {
            log.warn("OpenAI 임베딩 호출 실패", ex)
            throw upstreamError()
        }

        if (vector.isEmpty()) {
            log.warn("OpenAI 임베딩 응답이 비어 있습니다.")
            throw upstreamError()
        }
        if (vector.size != searchProperties.embedding.dimensions) {
            log.warn(
                "OpenAI 임베딩 차원이 일치하지 않습니다. expected={}, actual={}",
                searchProperties.embedding.dimensions,
                vector.size,
            )
            throw upstreamError()
        }
        return vector.toList()
    }

    private fun upstreamError() = BusinessException(ErrorCode.UPSTREAM_ERROR)
}
