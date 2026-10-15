/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.document.api

import com.osscontest.server.document.domain.IndexingStatus
import java.time.Instant

/** 버전 목록·상세 응답의 인덱싱 상태. 값의 규칙은 진행 상태 조회(`/indexing`) 응답과 같다. */
data class IndexingDetail(
    val status: IndexingStatus,
    val phase: String?,
    val attemptCount: Int,
    val chunkCount: Int?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val lastErrorMessage: String?,
    /** RETRY_WAIT 일 때만 값이 있다. */
    val nextRetryAt: Instant?,
    val lastErrorCode: String?,
)
