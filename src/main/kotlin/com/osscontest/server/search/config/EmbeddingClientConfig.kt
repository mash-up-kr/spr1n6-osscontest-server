package com.osscontest.server.search.config

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * 질의 임베딩 호출 전용 RestClient. 인덱싱 워커의 호출 코드를 재사용하는 게 아니라
 * 검색 서버 자체의 클라이언트를 별도로 둔다 (설계 문서 5.3절 "질의 임베딩 호출" 참고).
 * 검색은 실시간 요청 경로에 있어 인덱싱보다 짧은 타임아웃을 쓴다.
 *
 * Boot 4.1부터 `ClientHttpRequestFactorySettings`는 `HttpClientSettings`로 이름이 바뀌었다.
 */
@Configuration
class EmbeddingClientConfig(
    private val searchProperties: SearchProperties,
) {

    @Bean
    fun embeddingRestClient(builder: RestClient.Builder): RestClient {
        val settings = HttpClientSettings.defaults()
            .withConnectTimeout(Duration.ofMillis(searchProperties.embedding.connectTimeoutMs))
            .withReadTimeout(Duration.ofMillis(searchProperties.embedding.readTimeoutMs))
        val requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings)

        return builder
            .baseUrl(searchProperties.embedding.baseUrl)
            .requestFactory(requestFactory)
            .build()
    }
}
