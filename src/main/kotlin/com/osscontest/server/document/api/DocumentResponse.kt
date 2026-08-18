package com.osscontest.server.document.api

import com.osscontest.server.indexing.domain.IndexingStatus

data class DocumentUploadResponse(
    val documentId: Long,
    val versionNo: Long,
    /** 같은 내용을 올린 이전 버전. 없으면 null. */
    val duplicateOfVersionNo: Long?,
    val indexing: IndexingProgressResponse,
)

data class IndexingProgressResponse(
    val status: IndexingStatus,
)
