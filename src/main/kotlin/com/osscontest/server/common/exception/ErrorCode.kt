package com.osscontest.server.common.exception

import org.springframework.http.HttpStatus

/** 응답의 code 값이자 상태 코드 매핑. */
enum class ErrorCode(val status: HttpStatus, val message: String) {

    /** 공통 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    /** 문서 */

    /** 버전 */

    /** 인덱싱 */

    /** 검색 */

    /** 권한 */
    ;
}
