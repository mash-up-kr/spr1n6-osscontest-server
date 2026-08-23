package com.osscontest.server.mcp.config

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

/**
 * 클라이언트가 X-Search-User-Id 헤더로 신원을 보낼 수 있게 하기 위해, Spring AI가 기본
 * 제공하는 WebMvcStreamableServerTransportProvider(contextExtractor 없음)를 그대로 쓰지 않고 직접 재정의한다.
 * 여기서 요청별 McpTransportContext 에 담아둔 값은 SearchTools.resolveAuthContext()가
 * McpSyncRequestContext 를 통해 읽는다
 */
@Configuration
class SearchMcpTransportConfig {

    @Bean
    fun webMvcStreamableServerTransportProvider(
        jsonMapper: JsonMapper,
        properties: McpServerStreamableHttpProperties,
    ): WebMvcStreamableServerTransportProvider =
        WebMvcStreamableServerTransportProvider.builder()
            .jsonMapper(JacksonMcpJsonMapper(jsonMapper))
            .mcpEndpoint(properties.mcpEndpoint)
            .keepAliveInterval(properties.keepAliveInterval)
            .disallowDelete(properties.isDisallowDelete)
            .contextExtractor { request ->
                val userId = request.headers().firstHeader(SEARCH_USER_ID_HEADER)
                McpTransportContext.create(if (userId != null) mapOf(SEARCH_USER_ID_KEY to userId) else emptyMap())
            }
            .build()

    companion object {
        const val SEARCH_USER_ID_HEADER = "X-Search-User-Id"
        const val SEARCH_USER_ID_KEY = "searchUserId"
    }
}
