package com.osscontest.server.search.infrastructure

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.search.config.SearchProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body

/**
 * 검색 질의를 OpenAI Embeddings API로 변환한다. 문서 인덱싱과 동일한 모델·차원을 사용해야
 * 저장된 document_chunk.embedding과 의미 있는 유사도 비교가 가능하다.
 */
@Component
class EmbeddingClient(
    private val embeddingRestClient: RestClient,
    private val searchProperties: SearchProperties,
    private val retrySleeper: RetrySleeper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun embed(text: String): List<Float> {
        val embeddingProperties = searchProperties.embedding
        if (embeddingProperties.apiKey.isBlank()) {
            log.error("OPENAI_API_KEY가 설정되지 않았습니다.")
            throw upstreamError()
        }

        val response = requestWithRetry(text)
        val vector = response?.data?.firstOrNull()?.embedding
        if (vector.isNullOrEmpty()) {
            log.warn("OpenAI 임베딩 응답이 비어 있습니다.")
            throw upstreamError()
        }
        if (vector.size != embeddingProperties.dimensions) {
            log.warn(
                "OpenAI 임베딩 차원이 일치하지 않습니다. expected={}, actual={}",
                embeddingProperties.dimensions,
                vector.size,
            )
            throw upstreamError()
        }
        return vector
    }

    private fun requestWithRetry(text: String): EmbedResponse? {
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return request(text)
            } catch (ex: RestClientException) {
                if (!isRetryable(ex) || attempt == MAX_ATTEMPTS) {
                    log.warn(
                        "OpenAI 임베딩 호출 최종 실패. attempt={}/{}",
                        attempt,
                        MAX_ATTEMPTS,
                        ex,
                    )
                    break
                }

                val delayMs = searchProperties.embedding.retryBackoffMs.coerceAtLeast(0) * attempt
                log.warn(
                    "OpenAI 임베딩 호출 실패. attempt={}/{}, retryInMs={}",
                    attempt,
                    MAX_ATTEMPTS,
                    delayMs,
                )
                sleep(delayMs)
            }
        }

        throw upstreamError()
    }

    private fun request(text: String): EmbedResponse? {
        val embeddingProperties = searchProperties.embedding
        return embeddingRestClient.post()
            .uri("/embeddings")
            .headers { it.setBearerAuth(embeddingProperties.apiKey) }
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(
                EmbedRequest(
                    input = text,
                    model = embeddingProperties.model,
                    dimensions = embeddingProperties.dimensions,
                ),
            )
            .retrieve()
            .body<EmbedResponse>()
    }

    private fun isRetryable(ex: RestClientException): Boolean {
        if (ex !is RestClientResponseException) {
            return true
        }

        val status = ex.statusCode.value()
        return status == 408 || status == 409 || status == 429 || status >= 500
    }

    private fun sleep(delayMs: Long) {
        try {
            retrySleeper.sleep(delayMs)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("OpenAI 임베딩 재시도 대기 중 인터럽트가 발생했습니다.", ex)
            throw upstreamError()
        }
    }

    private fun upstreamError() = BusinessException(ErrorCode.UPSTREAM_ERROR)

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}

fun interface RetrySleeper {
    fun sleep(delayMs: Long)
}
