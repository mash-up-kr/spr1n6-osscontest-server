package com.osscontest.server.indexing.repository

import com.osscontest.server.indexing.domain.IndexingJob
import org.springframework.data.jpa.repository.JpaRepository

interface IndexingJobRepository : JpaRepository<IndexingJob, Long> {

    fun findFirstByDocumentVersionIdOrderByCreatedAtDesc(documentVersionId: Long): IndexingJob?

    fun findByDocumentVersionIdIn(documentVersionIds: Collection<Long>): List<IndexingJob>
}
