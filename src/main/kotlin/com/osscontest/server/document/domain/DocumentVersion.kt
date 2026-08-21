package com.osscontest.server.document.domain

import com.osscontest.server.common.domain.BaseCreatedAtEntity
import jakarta.persistence.*
import org.hibernate.annotations.ColumnTransformer
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "document_version")
class DocumentVersion(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @Column(name = "version_no", nullable = false)
    var versionNo: Long,

    @Column(name = "source_object_key", nullable = false, length = 1024)
    var sourceObjectKey: String,

    /**
     * 암호화 대상 컬럼이므로 VARBINARY 로 선언. 암복호화는 app_encrypt / app_decrypt 함수 시그니처 사용.
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @ColumnTransformer(read = "app_decrypt(original_filename)", write = "app_encrypt(?)")
    @Column(name = "original_filename", nullable = false)
    var originalFilename: String,

    @Column(name = "mime_type", nullable = false, length = 100)
    var mimeType: String,

    @Column(name = "file_size", nullable = false)
    var fileSize: Long,

    @Column(name = "content_hash", nullable = false, length = 128)
    var contentHash: String,

    @Column(name = "created_by_principal_id", nullable = false, length = 255)
    var createdByPrincipalId: String,

    ) : BaseCreatedAtEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @Column(name = "embedding_version_no", nullable = false)
    var embeddingVersionNo: Long = versionNo

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_metadata")
    var sourceMetadata: Map<String, Any?>? = null

    /** 파싱 결과가 아니라 AI 생성 메타데이터. Worker 가 채운다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_metadata")
    var extractedMetadata: Map<String, Any?>? = null

    @Column(name = "chunk_count")
    var chunkCount: Int? = null

    @Column(name = "indexed_at")
    var indexedAt: Instant? = null
}
