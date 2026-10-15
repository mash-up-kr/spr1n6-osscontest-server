package com.osscontest.server.config

import com.osscontest.server.common.web.AuthContextArgumentResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val authContextArgumentResolver: AuthContextArgumentResolver,
    @Value("\${cors.allowed-origins}") private val allowedOrigins: List<String>,
) : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(authContextArgumentResolver)
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*allowedOrigins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("X-User-Id", "Content-Type")
            // CORS 기본 허용 헤더가 아니면 브라우저가 스크립트에 넘기지 않는다. Retry-After 는 503 재시도 간격이다.
            .exposedHeaders("Content-Disposition", "Retry-After")
            // 프리플라이트 캐시. 크롬은 이 값과 무관하게 600초로 자른다.
            .maxAge(3600)
    }
}
