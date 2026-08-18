package com.osscontest.server.document.repository

import com.osscontest.server.document.domain.Document
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface DocumentRepository : JpaRepository<Document, Long> {

    /** 같은 문서에 대한 동시 버전 업로드가 순차적으로 번호를 받도록 문서 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT d
        FROM Document d
        WHERE d.id = :id
          AND d.tenant.id = :tenantId
          AND d.deletedAt IS NULL
          AND (
              d.ownerPrincipalId = :userPrincipalId
              OR EXISTS (
                  SELECT 1
                  FROM DocumentAccessScope s
                  WHERE s.document = d
                    AND s.tenantId = :tenantId
                    AND s.permission IN (
                        com.osscontest.server.document.domain.Permission.WRITE,
                        com.osscontest.server.document.domain.Permission.ADMIN
                    )
                    AND (
                        (
                            s.principalType = com.osscontest.server.document.domain.PrincipalType.TENANT
                            AND s.principalId = :tenantPrincipalId
                        )
                        OR (
                            s.principalType = com.osscontest.server.document.domain.PrincipalType.USER
                            AND s.principalId = :userPrincipalId
                        )
                    )
              )
          )
        """,
    )
    fun findActiveWritableForUpdate(
        id: Long,
        tenantId: Long,
        tenantPrincipalId: String,
        userPrincipalId: String,
    ): Document?
}
