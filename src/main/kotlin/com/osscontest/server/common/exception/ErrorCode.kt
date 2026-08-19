package com.osscontest.server.common.exception

import org.springframework.http.HttpStatus

/** 응답의 code 값이자 상태 코드 매핑. */
enum class ErrorCode(val status: HttpStatus, val message: String) {

    /** 공통 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    /** 문서 */
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다."),

    /** 버전 */
    DOCUMENT_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "문서 버전을 찾을 수 없습니다."),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 파일 형식입니다."),
    EMPTY_FILE(HttpStatus.BAD_REQUEST, "빈 파일은 업로드할 수 없습니다."),

    /** 인덱싱 */

    /** 검색 */
    INVALID_QUERY(HttpStatus.BAD_REQUEST, "검색 질의문이 비어 있습니다."),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "검색어 처리 중 오류가 발생했습니다."),

    /** 권한 */
    ;
}
