package com.osscontest.server.search.infrastructure

import com.osscontest.server.common.exception.ApiException
import com.osscontest.server.common.exception.ErrorCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body

/**
 * 질의 임베딩 호출. 인덱싱 파이프라인과 같은 `POST /embed` 계약을 그대로 쓴다 — 모델은 동일,
 * 호출자만 다르다. 인덱싱은 실패 시 비동기 재시도로 넘기지만, 검색은 사용자가 기다리는
 * 실시간 요청이라 실패하면 그 자리에서 바로 에러를 반환한다 (재시도 없음).
 */
@Component
class EmbeddingClient(
    private val embeddingRestClient: RestClient,
) {

    fun embed(text: String): List<Float> {
        val response = try {
            embeddingRestClient.post()
                .uri("/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(EmbedRequest(texts = listOf(text)))
                .retrieve()
                .body<EmbedResponse>()
        } catch (ex: RestClientException) {
            throw ApiException(ErrorCode.UPSTREAM_ERROR, cause = ex)
        }

        val vector = response?.vectors?.firstOrNull()
        if (vector.isNullOrEmpty()) {
            throw ApiException(ErrorCode.UPSTREAM_ERROR, message = "임베딩 응답이 비어 있습니다.")
        }
        return vector
    }
}
