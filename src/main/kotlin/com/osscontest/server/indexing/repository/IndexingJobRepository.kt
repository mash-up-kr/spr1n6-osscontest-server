package com.osscontest.server.indexing.repository

import com.osscontest.server.indexing.domain.IndexingJob
import com.osscontest.server.indexing.domain.JobType
import org.springframework.data.jpa.repository.JpaRepository

interface IndexingJobRepository : JpaRepository<IndexingJob, Long> {

    /** 버전의 최신 Job. 최신은 enqueue_seq 가 가장 큰 Job 이며 Worker 의 처리 순서와 같다. */
    fun findFirstByDocumentVersionIdAndJobTypeOrderByEnqueueSeqDesc(documentVersionId: Long, jobType: JobType): IndexingJob?

    /** 버전마다 Job 이 여러 건 온다. 최신 Job 은 호출하는 쪽이 enqueue_seq 로 고른다. */
    fun findByDocumentVersionIdInAndJobType(documentVersionIds: Collection<Long>, jobType: JobType): List<IndexingJob>
}
