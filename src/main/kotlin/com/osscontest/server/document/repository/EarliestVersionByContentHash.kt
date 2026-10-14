package com.osscontest.server.document.repository

/** 한 문서에서 같은 내용 해시를 가진 버전 중 가장 이른 버전 번호. */
interface EarliestVersionByContentHash {
    val contentHash: String
    val versionNo: Long
}
