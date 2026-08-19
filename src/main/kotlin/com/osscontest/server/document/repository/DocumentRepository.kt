package com.osscontest.server.document.repository

import com.osscontest.server.document.domain.Document
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface DocumentRepository : JpaRepository<Document, Long> {

    @Query(
        """
        SELECT d
        FROM Document d
        WHERE d.tenant.id = :tenantId
          AND d.deletedAt IS NULL
          AND (:cursorId IS NULL OR d.id < :cursorId)
        ORDER BY d.id DESC
        """,
    )
    fun findActivePage(tenantId: Long, cursorId: Long?, pageable: Pageable): List<Document>

    @Query(
        """
        SELECT d
        FROM Document d
        WHERE d.tenant.id = :tenantId
          AND d.deletedAt IS NULL
          AND (:cursorId IS NULL OR d.id < :cursorId)
          AND LOWER(d.title) LIKE :titlePattern ESCAPE '\'
        ORDER BY d.id DESC
        """,
    )
    fun findActivePageByTitle(
        tenantId: Long,
        cursorId: Long?,
        titlePattern: String,
        pageable: Pageable,
    ): List<Document>

    @Query("SELECT d FROM Document d WHERE d.id = :id AND d.tenant.id = :tenantId AND d.deletedAt IS NULL")
    fun findActive(id: Long, tenantId: Long): Document?

    /** 같은 문서에 대한 동시 버전 업로드가 순차적으로 번호를 받도록 문서 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :id AND d.tenant.id = :tenantId AND d.deletedAt IS NULL")
    fun findActiveForUpdate(id: Long, tenantId: Long): Document?

}
