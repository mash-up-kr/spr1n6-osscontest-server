package com.osscontest.server.indexing.api

import com.osscontest.server.document.api.IndexingProgress
import com.osscontest.server.document.domain.IndexingStatus
import java.time.Instant

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
