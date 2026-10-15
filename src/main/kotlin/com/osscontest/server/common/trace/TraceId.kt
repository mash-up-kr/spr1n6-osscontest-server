/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.common.trace

import org.slf4j.MDC

/** 요청 단위 추적 ID. 로그 패턴과 에러 응답, Job 등록 트리거가 같은 값을 본다. */
object TraceId {

    const val KEY = "traceId"

    fun current(): String? = MDC.get(KEY)
}
