package com.osscontest.server.common.web

import com.osscontest.server.common.trace.TraceId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** 요청마다 추적 ID 를 만들어 MDC 에 저장. */
@Component
class TraceIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        MDC.put(TraceId.KEY, UUID.randomUUID().toString().replace("-", "").take(16))
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(TraceId.KEY)
        }
    }
}
