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
import org.hibernate.annotations.Generated
import java.time.Instant
import java.util.UUID

/**
 * Worker 가 claim 해 처리하는 인덱싱 Job. 스키마는 Server 가 소유한다.
 *
 * Server 는 PENDING 으로 등록만 하고 상태 전이는 Worker 가 한다.
 * execution_epoch·lease_until·outcome 은 Worker 만 읽어 매핑하지 않는다. INSERT 에서 빠지면 DB 기본값이 들어간다.
 */
@Entity
@Table(name = "indexing_job")
class IndexingJob(

    /** Kafka 로 Job 을 받던 때 원본 이벤트 id 를 담던 컬럼이다. 지금은 등록할 때마다 새 UUID 를 넣고 FK 도 없다. */
    @Column(name = "source_event_id", nullable = false)
    var sourceEventId: UUID,

    @Column(name = "document_id", nullable = false)
    var documentId: Long,

    /** DELETE Job 은 문서 단위라 NULL 이다. */
    @Column(name = "document_version_id")
    var documentVersionId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: IndexingStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 20)
    var jobType: JobType = JobType.INDEX,

) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    /**
     * 등록 순서. Worker 가 같은 문서의 Job 을 이 순서로 처리한다.
     * DB 가 발급하므로 INSERT 직후 다시 읽는다. 같은 트랜잭션에서 최신 Job 을 고를 때 NULL 이면 안 된다.
     */
    @Generated
    @Column(name = "enqueue_seq", insertable = false, updatable = false)
    var enqueueSeq: Long? = null

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
