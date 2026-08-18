package com.osscontest.server.outbox.domain

enum class OutboxStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    DEAD,
}
