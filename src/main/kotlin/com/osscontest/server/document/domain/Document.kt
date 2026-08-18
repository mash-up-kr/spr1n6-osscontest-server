package com.osscontest.server.document.domain

import com.osscontest.server.common.domain.BaseTimeEntity
import com.osscontest.server.tenant.domain.Tenant
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "document")
class Document(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    var tenant: Tenant,

    @Column(name = "owner_principal_id", nullable = false, length = 255)
    var ownerPrincipalId: String,

    @Column(name = "title", nullable = false, length = 255)
    var title: String,

) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @Column(name = "latest_upload_version_no", nullable = false)
    var latestUploadVersionNo: Long = 0

    @Column(name = "latest_embedding_version_no", nullable = false)
    var latestEmbeddingVersionNo: Long = 0

    @Column(name = "searchable_version_id")
    var searchableVersionId: Long? = null

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    /** 물리 정리 완료 시각. Worker 가 기록. */
    @Column(name = "purged_at")
    var purgedAt: Instant? = null
}
