package com.osscontest.server.common.trace

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

/** 트리거는 애플리케이션 컨텍스트를 못 보므로 추적 ID 를 트랜잭션 설정으로 넘긴다. */
@Component
class DbTraceIdBinder(
    private val entityManager: EntityManager,
) {

    fun bind() {
        val traceId = TraceId.current() ?: return

        entityManager.createNativeQuery("SELECT set_config('app.trace_id', :traceId, true)")
            .setParameter("traceId", traceId)
            .singleResult
    }
}
