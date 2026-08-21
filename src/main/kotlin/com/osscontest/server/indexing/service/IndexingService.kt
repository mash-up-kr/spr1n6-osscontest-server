package com.osscontest.server.indexing.service

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.trace.TraceId
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentVersion
import com.osscontest.server.document.repository.DocumentVersionRepository
import com.osscontest.server.document.service.DocumentAccessChecker
import com.osscontest.server.indexing.api.IndexingProgress
import com.osscontest.server.indexing.api.IndexingRetryResponse
import com.osscontest.server.indexing.api.IndexingStatusResponse
import com.osscontest.server.indexing.domain.IndexingJob
import com.osscontest.server.indexing.domain.IndexingStatus
import com.osscontest.server.indexing.repository.IndexingJobRepository
import com.osscontest.server.outbox.domain.OutboxEvent
import com.osscontest.server.outbox.domain.OutboxEventType
import com.osscontest.server.outbox.domain.OutboxStatus
import com.osscontest.server.outbox.repository.OutboxEventRepository
import com.osscontest.server.outbox.service.OutboxService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 버전별 인덱싱 진행 상태 조회와 재인덱싱 요청.
 *
 * outbox_event 와 indexing_job 은 릴레이·워커가 쓰고 API 서버는 읽기만 한다.
 * 예외는 재인덱싱 요청으로, 이때만 애플리케이션이 Outbox 에 행을 넣는다.
 */
@Service
class IndexingService(
    private val outboxEventRepository: OutboxEventRepository,
    private val indexingJobRepository: IndexingJobRepository,
    private val documentVersionRepository: DocumentVersionRepository,
    private val documentAccessChecker: DocumentAccessChecker,
    private val outboxService: OutboxService,
) {

    @Transactional(readOnly = true)
    fun getStatus(authContext: AuthContext, documentId: Long, versionNo: Long): IndexingStatusResponse {
        val document = documentAccessChecker.requireReadable(authContext, documentId)
        val version = findVersion(document, versionNo)
        val indexing = state(version)

        return IndexingStatusResponse(
            versionNo = version.versionNo,
            status = indexing.status,
            phase = indexing.phase,
            attemptCount = indexing.attemptCount,
            chunkCount = version.chunkCount,
            startedAt = indexing.startedAt,
            completedAt = indexing.completedAt,
            lastErrorMessage = indexing.lastErrorMessage,
        )
    }

    @Transactional
    fun retry(authContext: AuthContext, documentId: Long, versionNo: Long): IndexingRetryResponse {
        val document = documentAccessChecker.requireWritableForUpdate(authContext, documentId)
        val version = findVersion(document, versionNo)
        val latestEvent = latestRequestedEvent(version)
        val job = latestEvent?.let { indexingJobRepository.findBySourceEventId(it.id) }

        if (job?.status != IndexingStatus.FAILED) {
            throw BusinessException(ErrorCode.INDEXING_RETRY_NOT_ALLOWED)
        }
        if (hasPendingRetryEvent(version)) {
            throw BusinessException(ErrorCode.INDEXING_RETRY_ALREADY_REQUESTED)
        }

        outboxService.publish(retryEvent(document, version, job.sourceEventId))

        return IndexingRetryResponse(
            versionNo = version.versionNo,
            indexing = IndexingProgress(IndexingStatus.PENDING),
        )
    }

    /** 문서·버전 목록에 붙일 상태. 버전 수와 무관하게 이벤트와 잡을 한 번씩만 조회한다. */
    @Transactional(readOnly = true)
    fun statusByVersionId(versionIds: Collection<Long>): Map<Long?, IndexingStatus> =
        stateByVersionId(versionIds).mapValues { it.value.status }

    @Transactional(readOnly = true)
    fun statusOf(version: DocumentVersion): IndexingStatus = state(version).status

    private fun findVersion(document: Document, versionNo: Long): DocumentVersion =
        documentVersionRepository.findByDocumentIdAndVersionNo(document.id!!, versionNo)
            ?: throw BusinessException(ErrorCode.DOCUMENT_VERSION_NOT_FOUND)

    private fun state(version: DocumentVersion): IndexingState {
        val latestEvent = latestRequestedEvent(version)
        val job = latestEvent?.let { indexingJobRepository.findBySourceEventId(it.id) }

        return job?.toIndexingState()
            ?: latestEvent?.toPendingIndexingState()
            ?: IndexingState(status = IndexingStatus.PENDING)
    }

    private fun stateByVersionId(versionIds: Collection<Long>): Map<Long?, IndexingState> {
        if (versionIds.isEmpty()) return emptyMap()

        val latestEventByVersionId = latestRequestedEventByVersionId(versionIds)
        if (latestEventByVersionId.isEmpty()) return emptyMap()

        val jobBySourceEventId =
            indexingJobRepository.findBySourceEventIdIn(latestEventByVersionId.values.map { it.id })
                .associateBy { it.sourceEventId }

        return latestEventByVersionId.mapValues { (_, event) ->
            jobBySourceEventId[event.id]?.toIndexingState() ?: event.toPendingIndexingState()
        }
    }

    private fun latestRequestedEventByVersionId(versionIds: Collection<Long>): Map<Long?, OutboxEvent> =
        outboxEventRepository.findByDocumentVersionIdInAndEventType(
            documentVersionIds = versionIds,
            eventType = OutboxEventType.INDEXING_REQUESTED,
        )
            .groupBy { it.documentVersionId }
            .mapValues { (_, events) -> events.latestByCreatedAt() }

    private fun List<OutboxEvent>.latestByCreatedAt(): OutboxEvent =
        maxWith(compareBy<OutboxEvent> { it.createdAt }.thenBy { it.id })

    private fun latestRequestedEvent(version: DocumentVersion): OutboxEvent? =
        outboxEventRepository.findFirstByDocumentVersionIdAndEventTypeOrderByCreatedAtDesc(
            documentVersionId = version.id!!,
            eventType = OutboxEventType.INDEXING_REQUESTED,
        )

    private fun hasPendingRetryEvent(version: DocumentVersion): Boolean =
        outboxEventRepository.existsRetryEvent(
            documentVersionId = version.id!!,
            eventType = OutboxEventType.INDEXING_REQUESTED,
            statuses = listOf(OutboxStatus.PENDING, OutboxStatus.PUBLISHING),
        )

    private fun retryEvent(document: Document, version: DocumentVersion, retryOfEventId: UUID): OutboxEvent {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val event = OutboxEvent(
            id = UUID.randomUUID(),
            tenantId = document.tenant.id!!,
            documentId = document.id!!,
            eventType = OutboxEventType.INDEXING_REQUESTED,
            payload = mapOf(
                "tenantId" to document.tenant.id!!,
                "versionNo" to version.versionNo,
                "sourceObjectKey" to version.sourceObjectKey,
                "mimeType" to version.mimeType,
                "fileSize" to version.fileSize,
                "contentHash" to version.contentHash,
                "occurredAt" to now.toString(),
            ),
        )

        event.documentVersionId = version.id
        event.retryOfEventId = retryOfEventId
        event.traceId = TraceId.current()
        event.nextAttemptAt = now

        return event
    }

    private fun IndexingJob.toIndexingState(): IndexingState =
        IndexingState(
            status = status,
            phase = phase,
            attemptCount = attemptCount,
            startedAt = startedAt,
            completedAt = completedAt,
            lastErrorMessage = lastErrorMessage,
        )

    private fun OutboxEvent.toPendingIndexingState(): IndexingState =
        IndexingState(
            status = IndexingStatus.PENDING,
            lastErrorMessage = when {
                status == OutboxStatus.DEAD -> lastErrorMessage
                status == OutboxStatus.PENDING && publishAttemptCount > 0 -> lastErrorMessage
                else -> null
            },
        )

    private data class IndexingState(
        val status: IndexingStatus,
        val phase: String? = null,
        val attemptCount: Int = 0,
        val startedAt: Instant? = null,
        val completedAt: Instant? = null,
        val lastErrorMessage: String? = null,
    )
}
