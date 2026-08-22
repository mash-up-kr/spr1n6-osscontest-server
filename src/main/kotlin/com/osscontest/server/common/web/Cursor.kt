package com.osscontest.server.common.web

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import java.util.Base64

/**
 * 커서 페이징의 커서 값 인코딩. 정렬 키 한 개를 담은 JSON 을 URL-safe Base64 로 감싼다.
 *
 * 정렬 키 이름은 부르는 쪽이 정한다. 목록마다 키가 달라 (문서는 id, 버전은 versionNo)
 * 다른 목록에서 받은 커서를 넘기면 디코딩에서 걸린다.
 */
object Cursor {

    fun encode(field: String, value: Long): String {
        val json = """{"$field":$value}"""
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    /** 비어 있으면 null 을 돌려주고, 형식이 어긋나면 INVALID_REQUEST 로 끝낸다. */
    fun decode(cursor: String?, field: String): Long? {
        if (cursor.isNullOrBlank()) return null

        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(cursor))
            val value = json.substringAfter("\"$field\":", missingDelimiterValue = "")
                .substringBefore("}")
                .trim()

            value.toLong()
        }.getOrElse { throw BusinessException(ErrorCode.INVALID_REQUEST) }
    }
}
