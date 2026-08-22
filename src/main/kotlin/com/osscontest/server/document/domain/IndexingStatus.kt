package com.osscontest.server.document.domain

/**
 * 버전의 인덱싱 진행 상태. 문서·버전 응답에 실리는 값이라 document 가 계약을 소유한다.
 *
 * 값을 실제로 전이시키는 것은 Worker 이고 indexing_job.status 갱신도 Worker 소유다.
 * API 서버는 읽기만 한다.
 */
enum class IndexingStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETED,
    FAILED,
}
