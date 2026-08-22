package com.osscontest.server.document.api

import com.osscontest.server.document.domain.IndexingStatus

/** 문서·버전 응답과 재인덱싱 응답에 함께 실리는 진행 상태. */
data class IndexingProgress(
    val status: IndexingStatus,
)
