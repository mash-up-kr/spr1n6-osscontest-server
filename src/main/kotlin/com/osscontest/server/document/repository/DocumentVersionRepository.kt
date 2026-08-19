package com.osscontest.server.document.repository

import com.osscontest.server.document.domain.DocumentVersion
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DocumentVersionRepository : JpaRepository<DocumentVersion, Long> {

    /** 같은 내용을 올린 가장 이른 버전. 직전 버전이 아니라 모든 버전과 대조한다. */
    @Query(
        "SELECT MIN(v.versionNo) FROM DocumentVersion v " +
            "WHERE v.document.id = :documentId AND v.contentHash = :contentHash",
    )
    fun findEarliestVersionNoByContentHash(documentId: Long, contentHash: String): Long?

    fun findByDocumentIdAndVersionNo(documentId: Long, versionNo: Long): DocumentVersion?

    fun findByDocumentIdInAndVersionNoIn(
        documentIds: Collection<Long>,
        versionNos: Collection<Long>,
    ): List<DocumentVersion>

    @Query(
        """
        SELECT v
        FROM DocumentVersion v
        WHERE v.document.id = :documentId
          AND (:cursorVersionNo IS NULL OR v.versionNo < :cursorVersionNo)
        ORDER BY v.versionNo DESC
        """,
    )
    fun findPageByDocumentId(documentId: Long, cursorVersionNo: Long?, pageable: Pageable): List<DocumentVersion>
}
