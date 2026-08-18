package com.osscontest.server.common.trace

import org.slf4j.MDC

/** 요청 단위 추적 ID. 로그 패턴과 에러 응답, Outbox 트리거가 같은 값을 본다. */
object TraceId {

    const val KEY = "traceId"

    fun current(): String? = MDC.get(KEY)
}
