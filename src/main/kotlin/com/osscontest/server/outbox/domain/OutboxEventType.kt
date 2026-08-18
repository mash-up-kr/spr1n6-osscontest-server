package com.osscontest.server.outbox.domain

enum class OutboxEventType {
    INDEXING_REQUESTED,
    DOCUMENT_DELETED,
}
