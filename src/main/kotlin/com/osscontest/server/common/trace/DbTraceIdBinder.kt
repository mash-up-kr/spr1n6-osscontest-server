package com.osscontest.server.common.trace

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

/** 트리거는 애플리케이션 컨텍스트를 못 보므로 추적 ID 를 트랜잭션 설정으로 넘긴다. */
@Component
class DbTraceIdBinder(
    private val entityManager: EntityManager,
) {

    fun bind() {
        // 추적 ID 는 요청 필터가 넣는다. HTTP 요청 밖(테스트·배치)에서는 없는 것이 정상이라 그냥 넘어간다.
        // 이때 트리거가 만드는 Outbox 행의 trace_id 는 NULL 이 된다.
        val traceId = TraceId.current() ?: return

        entityManager.createNativeQuery("SELECT set_config('app.trace_id', :traceId, true)")
            .setParameter("traceId", traceId)
            .singleResult
    }
}
