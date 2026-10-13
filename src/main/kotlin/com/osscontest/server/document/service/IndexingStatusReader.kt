package com.osscontest.server.document.service

import com.osscontest.server.document.domain.DocumentVersion
import com.osscontest.server.document.domain.IndexingStatus

/**
 * 문서·버전 응답에 붙일 인덱싱 상태 조회 창구.
 *
 * 필요한 모양을 쓰는 쪽에서 선언해 document 가 indexing 을 직접 알지 않게 한다.
 * 구현은 indexing 이 갖는다.
 */
interface IndexingStatusReader {

    fun statusOf(version: DocumentVersion): IndexingStatus

    /** 버전 수와 무관하게 Job 을 한 번만 조회한다. Job 이 없는 버전은 결과에 없다. */
    fun statusByVersionId(versionIds: Collection<Long>): Map<Long?, IndexingStatus>
}
