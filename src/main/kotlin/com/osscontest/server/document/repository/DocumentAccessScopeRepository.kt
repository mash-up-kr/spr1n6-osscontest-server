package com.osscontest.server.document.repository

import com.osscontest.server.document.domain.DocumentAccessScope
import com.osscontest.server.document.domain.Permission
import com.osscontest.server.document.domain.PrincipalType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DocumentAccessScopeRepository : JpaRepository<DocumentAccessScope, Long> {

    fun findByDocumentIdOrderByIdAsc(documentId: Long): List<DocumentAccessScope>

    fun findByDocumentIdAndPrincipalTypeAndPrincipalId(
        documentId: Long,
        principalType: PrincipalType,
        principalId: String,
    ): DocumentAccessScope?

    /** 요청자 본인과 요청자가 속한 테넌트에 부여된 권한. 둘 다 있으면 두 건이 나온다. */
    @Query(
        """
        SELECT s.permission
        FROM DocumentAccessScope s
        WHERE s.document.id = :documentId
          AND s.tenantId = :tenantId
          AND (
              (s.principalType = com.osscontest.server.document.domain.PrincipalType.USER
                  AND s.principalId = :userPrincipalId)
              OR (s.principalType = com.osscontest.server.document.domain.PrincipalType.TENANT
                  AND s.principalId = :tenantPrincipalId)
          )
        """,
    )
    fun findGrantedPermissions(
        documentId: Long,
        tenantId: Long,
        tenantPrincipalId: String,
        userPrincipalId: String,
    ): List<Permission>

    /** [findGrantedPermissions] 의 조건을 여러 문서에 한 번에 적용한다. */
    @Query(
        """
        SELECT s.document.id AS documentId, s.permission AS permission
        FROM DocumentAccessScope s
        WHERE s.document.id IN :documentIds
          AND s.tenantId = :tenantId
          AND (
              (s.principalType = com.osscontest.server.document.domain.PrincipalType.USER
                  AND s.principalId = :userPrincipalId)
              OR (s.principalType = com.osscontest.server.document.domain.PrincipalType.TENANT
                  AND s.principalId = :tenantPrincipalId)
          )
        """,
    )
    fun findGrantedPermissionsByDocumentIds(
        documentIds: Collection<Long>,
        tenantId: Long,
        tenantPrincipalId: String,
        userPrincipalId: String,
    ): List<DocumentPermissionGrant>
}
