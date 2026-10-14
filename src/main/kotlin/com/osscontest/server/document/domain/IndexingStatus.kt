package com.osscontest.server.document.domain

/**
 * 버전의 인덱싱 진행 상태. 문서·버전 응답에 실리는 값이라 document 가 계약을 소유한다.
 *
 * Server 는 indexing_job 을 PENDING 으로 등록만 하고, 이후 전이는 Worker 가 한다.
 */
enum class IndexingStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETED,
    FAILED,
}
