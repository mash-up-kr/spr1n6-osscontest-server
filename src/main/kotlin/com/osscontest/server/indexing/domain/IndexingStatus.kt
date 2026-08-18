package com.osscontest.server.indexing.domain

enum class IndexingStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETED,
    FAILED,
}
