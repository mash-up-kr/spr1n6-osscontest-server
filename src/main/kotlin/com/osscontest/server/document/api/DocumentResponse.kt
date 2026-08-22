package com.osscontest.server.document.api

import com.osscontest.server.document.domain.IndexingStatus
import java.time.Instant

data class DocumentUploadResponse(
    val documentId: Long,
    val versionNo: Long,
    /** 같은 내용을 올린 이전 버전. 없으면 null. */
    val duplicateOfVersionNo: Long?,
    val indexing: IndexingProgress,
)

data class DocumentSummary(
    val id: Long,
    val title: String,
    val latestUploadVersionNo: Long,
    val latestEmbeddingVersionNo: Long,
    val searchableVersionNo: Long?,
    val latestVersionIndexingStatus: IndexingStatus,
    val createdAt: Instant,
)

data class DocumentTitleResponse(
    val id: Long,
    val title: String,
)

data class SearchableVersionResponse(
    val searchableVersionNo: Long,
)

data class DocumentVersionSummary(
    val versionNo: Long,
    val originalFilename: String,
    val mimeType: String,
    val fileSize: Long,
    val uploadedAt: Instant,
    val indexing: IndexingProgress,
    val searchable: Boolean,
)

data class DocumentVersionDetailResponse(
    val versionNo: Long,
    val originalFilename: String,
    val mimeType: String,
    val fileSize: Long,
    val uploadedAt: Instant,
    val indexing: IndexingProgress,
    val searchable: Boolean,
    val sourceMetadata: Map<String, Any?>?,
    val extractedMetadata: Map<String, Any?>?,
)
