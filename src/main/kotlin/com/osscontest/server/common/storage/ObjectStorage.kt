package com.osscontest.server.common.storage

import java.io.InputStream

/** 원본 파일 저장소. 키 규칙은 저장소가 아니라 호출하는 쪽이 정한다. */
interface ObjectStorage {

    fun put(key: String, content: InputStream, contentLength: Long, contentType: String)

    /** 호출한 쪽이 스트림을 닫아야 한다. */
    fun get(key: String): InputStream
}
