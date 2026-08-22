package com.osscontest.server.outbox.repository

import com.osscontest.server.outbox.domain.OutboxEvent
import com.osscontest.server.outbox.domain.OutboxEventType
import com.osscontest.server.outbox.domain.OutboxStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    fun findByDocumentVersionIdInAndEventType(
        documentVersionIds: Collection<Long>,
        eventType: OutboxEventType,
    ): List<OutboxEvent>

    @Query(
        """
        SELECT COUNT(e) > 0
        FROM OutboxEvent e
        WHERE e.documentVersionId = :documentVersionId
          AND e.eventType = :eventType
          AND e.retryOfEventId IS NOT NULL
          AND e.status IN :statuses
        """,
    )
    fun existsRetryEvent(
        documentVersionId: Long,
        eventType: OutboxEventType,
        statuses: Collection<OutboxStatus>,
    ): Boolean
}
