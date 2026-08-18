package com.osscontest.server.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청마다 추적 ID 를 만들어 MDC 에 저장.
 * 에러 응답의 traceId 와 로그가 같은 값을 공유.
 */
@Component
class TraceIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        MDC.put(TRACE_ID, UUID.randomUUID().toString().replace("-", "").take(16))
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(TRACE_ID)
        }
    }

    companion object {
        const val TRACE_ID = "traceId"

        fun currentTraceId(): String? = MDC.get(TRACE_ID)
    }
}
