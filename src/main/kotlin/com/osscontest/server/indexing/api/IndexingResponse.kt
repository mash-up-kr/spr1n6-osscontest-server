package com.osscontest.server.indexing.api

import com.osscontest.server.indexing.domain.IndexingStatus
import java.time.Instant

/** 문서·버전 응답에도 실리는 진행 상태. */
data class IndexingProgress(
    val status: IndexingStatus,
)

data class IndexingStatusResponse(
    val versionNo: Long,
    val status: IndexingStatus,
    val phase: String?,
    val attemptCount: Int,
    val chunkCount: Int?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val lastErrorMessage: String?,
)

data class IndexingRetryResponse(
    val versionNo: Long,
    val indexing: IndexingProgress,
)
