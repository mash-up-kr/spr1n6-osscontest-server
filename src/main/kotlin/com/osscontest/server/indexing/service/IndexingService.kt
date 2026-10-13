package com.osscontest.server.indexing.service

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.trace.TraceId
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.document.api.IndexingProgress
import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentVersion
import com.osscontest.server.document.domain.IndexingStatus
import com.osscontest.server.document.repository.DocumentVersionRepository
import com.osscontest.server.document.service.DocumentAccessChecker
import com.osscontest.server.document.service.IndexingStatusReader
import com.osscontest.server.indexing.api.IndexingRetryResponse
import com.osscontest.server.indexing.api.IndexingStatusResponse
import com.osscontest.server.indexing.domain.IndexingJob
import com.osscontest.server.indexing.domain.JobType
import com.osscontest.server.indexing.repository.IndexingJobRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 버전별 인덱싱 진행 상태 조회와 재인덱싱 요청.
 *
 * 상태는 버전의 최신 INDEX Job(enqueue_seq 최대)이다. Job 은 버전 저장·문서 삭제 트리거와
 * 재인덱싱 요청이 등록하고, 상태 전이는 Worker 가 한다.
 */
@Service
class IndexingService(
    private val indexingJobRepository: IndexingJobRepository,
    private val documentVersionRepository: DocumentVersionRepository,
    private val documentAccessChecker: DocumentAccessChecker,
) : IndexingStatusReader {

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
        // 문서 행을 잠가 같은 버전의 재시도를 직렬화한다. Worker 와 같은 문서 → Job 순서다.
        val document = documentAccessChecker.requireWritableForUpdate(authContext, documentId)
        val version = findVersion(document, versionNo)

        // 미종결 Job 이 있으면 새 Job 이 uq_indexing_job_active_version 에 걸리고, 완료된 버전은 다시 돌릴 이유가 없다.
        if (latestJob(version.id!!)?.status != IndexingStatus.FAILED) {
            throw BusinessException(ErrorCode.INDEXING_RETRY_NOT_ALLOWED)
        }

        indexingJobRepository.save(
            IndexingJob(
                // 원본 이벤트가 없다. 등록 트리거의 gen_random_uuid() 와 같이 새 값을 넣는다.
                sourceEventId = UUID.randomUUID(),
                documentId = document.id!!,
                documentVersionId = version.id,
                status = IndexingStatus.PENDING,
                jobType = JobType.INDEX,
            ).also { it.traceId = TraceId.current() },
        )

        return IndexingRetryResponse(
            versionNo = version.versionNo,
            indexing = IndexingProgress(IndexingStatus.PENDING),
        )
    }

    @Transactional(readOnly = true)
    override fun statusByVersionId(versionIds: Collection<Long>): Map<Long?, IndexingStatus> =
        stateByVersionId(versionIds).mapValues { it.value.status }

    @Transactional(readOnly = true)
    override fun statusOf(version: DocumentVersion): IndexingStatus = state(version).status

    private fun findVersion(document: Document, versionNo: Long): DocumentVersion =
        documentVersionRepository.findByDocumentIdAndVersionNo(document.id!!, versionNo)
            ?: throw BusinessException(ErrorCode.DOCUMENT_VERSION_NOT_FOUND)

    /** Job 등록 트리거 도입 전에 올라온 버전은 Job 이 없어 PENDING 으로 본다. */
    private fun state(version: DocumentVersion): IndexingState =
        latestJob(version.id!!)?.toIndexingState() ?: IndexingState(status = IndexingStatus.PENDING)

    /** 단건 조회와 같은 규칙(enqueue_seq 최대)으로 버전별 최신 Job 을 한 번에 고른다. */
    private fun stateByVersionId(versionIds: Collection<Long>): Map<Long?, IndexingState> {
        if (versionIds.isEmpty()) return emptyMap()

        return indexingJobRepository.findByDocumentVersionIdInAndJobType(versionIds, JobType.INDEX)
            .groupBy { it.documentVersionId }
            .mapValues { (_, jobs) -> jobs.maxBy { it.enqueueSeq!! }.toIndexingState() }
    }

    private fun latestJob(versionId: Long): IndexingJob? =
        indexingJobRepository.findFirstByDocumentVersionIdAndJobTypeOrderByEnqueueSeqDesc(versionId, JobType.INDEX)

    private fun IndexingJob.toIndexingState(): IndexingState =
        IndexingState(
            status = status,
            phase = phase,
            attemptCount = attemptCount,
            startedAt = startedAt,
            completedAt = completedAt,
            lastErrorMessage = lastErrorMessage,
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
