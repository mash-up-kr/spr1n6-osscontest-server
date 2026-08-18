package com.osscontest.server.document.domain

import com.osscontest.server.common.domain.BaseCreatedAtEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "document_access_scope")
class DocumentAccessScope(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 30)
    var principalType: PrincipalType,

    @Column(name = "principal_id", nullable = false, length = 255)
    var principalId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 20)
    var permission: Permission,

    @Column(name = "granted_by_principal_id", nullable = false, length = 255)
    var grantedByPrincipalId: String,

) : BaseCreatedAtEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @Column(name = "tenant_id", nullable = false)
    var tenantId: Long = requireNotNull(document.tenant.id) {
        "저장되지 않은 문서에는 권한을 부여할 수 없습니다"
    }
}
