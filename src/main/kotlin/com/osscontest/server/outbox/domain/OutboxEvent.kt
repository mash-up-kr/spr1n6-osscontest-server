package com.osscontest.server.outbox.domain

import com.osscontest.server.common.domain.BaseCreatedAtEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * 업로드 건은 document_version INSERT 트리거가 생성. 애플리케이션 INSERT 는 재인덱싱뿐.
 * status 이하는 릴레이 소유.
 */
@Entity
@Table(name = "outbox_event")
class OutboxEvent(

    @Id
    @Column(name = "id")
    var id: UUID,

    @Column(name = "tenant_id", nullable = false)
    var tenantId: Long,

    @Column(name = "document_id", nullable = false)
    var documentId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    var eventType: OutboxEventType,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    var payload: Map<String, Any?>,

) : BaseCreatedAtEntity() {

    /** DOCUMENT_DELETED 는 NULL. */
    @Column(name = "document_version_id")
    var documentVersionId: Long? = null

    @Column(name = "retry_of_event_id")
    var retryOfEventId: UUID? = null

    @Column(name = "event_schema_version", nullable = false)
    var eventSchemaVersion: Int = 1

    @Column(name = "trace_id", length = 255)
    var traceId: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING

    @Column(name = "publish_attempt_count", nullable = false)
    var publishAttemptCount: Int = 0

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now()

    @Column(name = "locked_by", length = 255)
    var lockedBy: String? = null

    @Column(name = "locked_at")
    var lockedAt: Instant? = null

    @Column(name = "published_at")
    var publishedAt: Instant? = null

    @Column(name = "last_error_message", columnDefinition = "text")
    var lastErrorMessage: String? = null
}
