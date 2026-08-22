package com.osscontest.server.indexing.domain

import com.osscontest.server.common.domain.BaseTimeEntity
import com.osscontest.server.document.domain.IndexingStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Worker 소유. API 서버는 읽기 전용. source_event_id 에 FK 없음. */
@Entity
@Table(name = "indexing_job")
class IndexingJob(

    @Column(name = "source_event_id", nullable = false)
    var sourceEventId: UUID,

    @Column(name = "document_id", nullable = false)
    var documentId: Long,

    @Column(name = "document_version_id", nullable = false)
    var documentVersionId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: IndexingStatus,

) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = null

    @Column(name = "worker_id", length = 255)
    var workerId: String? = null

    @Column(name = "last_error_code", length = 100)
    var lastErrorCode: String? = null

    @Column(name = "last_error_message", columnDefinition = "text")
    var lastErrorMessage: String? = null

    @Column(name = "trace_id", length = 255)
    var traceId: String? = null

    @Column(name = "phase")
    var phase: String? = null

    @Column(name = "started_at")
    var startedAt: Instant? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null
}
