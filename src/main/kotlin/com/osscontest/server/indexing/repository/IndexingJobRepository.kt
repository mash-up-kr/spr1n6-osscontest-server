package com.osscontest.server.indexing.repository

import com.osscontest.server.indexing.domain.IndexingJob
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IndexingJobRepository : JpaRepository<IndexingJob, Long> {

    fun findFirstByDocumentVersionIdOrderByCreatedAtDesc(documentVersionId: Long): IndexingJob?

    fun findBySourceEventId(sourceEventId: UUID): IndexingJob?

    fun findBySourceEventIdIn(sourceEventIds: Collection<UUID>): List<IndexingJob>

    fun findByDocumentVersionIdIn(documentVersionIds: Collection<Long>): List<IndexingJob>
}
