package com.osscontest.server.common.web

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.trace.TraceId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/**
 * 예외를 code · message · traceId 형식의 응답으로 변환.
 *
 * - BusinessException: ErrorCode 가 정한 상태
 * - 본문 검증 실패와 파라미터 제약 위반: 400 과 위반한 항목 이름
 * - 스프링 MVC 예외: 404 · 405 · 415 등 MVC 계약 보존
 * - DB 장애 전환 중 연결 실패: 503 과 Retry-After
 * - 예상치 못한 예외: 500
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.errorCode.status)
            .body(errorResponse(e.errorCode.name, e.message))

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(e: Exception): ResponseEntity<ErrorResponse> {
        if (isDbUnavailable(e)) {
            log.warn("DB 에 일시적으로 접근할 수 없음", e)
            return ResponseEntity.status(ErrorCode.DB_UNAVAILABLE.status)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString())
                .body(errorResponse(ErrorCode.DB_UNAVAILABLE.name, ErrorCode.DB_UNAVAILABLE.message))
        }
        log.error("처리하지 못한 예외", e)
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status)
            .body(errorResponse(ErrorCode.INTERNAL_ERROR.name, ErrorCode.INTERNAL_ERROR.message))
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val message = ex.bindingResult.fieldErrors
            .joinToString { "${it.field}: ${it.defaultMessage}" }
            .ifBlank { ErrorCode.INVALID_REQUEST.message }

        return ResponseEntity.status(status)
            .body(errorResponse(ErrorCode.INVALID_REQUEST.name, message))
    }

    /** 파라미터 제약 위반. 본문 검증과 같은 수준으로 위반한 항목과 이유를 전달. */
    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val message = ex.parameterValidationResults
            .flatMap { result ->
                result.resolvableErrors.map { "${result.methodParameter.parameterName}: ${it.defaultMessage}" }
            }
            .joinToString()
            .ifBlank { ErrorCode.INVALID_REQUEST.message }

        return ResponseEntity.status(status)
            .body(errorResponse(ErrorCode.INVALID_REQUEST.name, message))
    }

    /** 스프링 MVC 예외의 본문도 같은 형식으로 통일. 상태 코드는 MVC 가 정한 값 그대로. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        ResponseEntity.status(statusCode)
            .body(
                errorResponse(
                    code = HttpStatus.valueOf(statusCode.value()).name,
                    message = ex.message ?: ErrorCode.INVALID_REQUEST.message,
                ),
            )

    private fun errorResponse(code: String, message: String) =
        ErrorResponse(code = code, message = message, traceId = TraceId.current())

    // 같은 DB 오류도 JPA·JdbcTemplate·Hibernate 가 각기 다른 예외로 감싸므로 원인 체인의 SQLException 으로 판단한다.
    private fun isDbUnavailable(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { cause ->
            cause is SQLTransientConnectionException ||
                (cause is SQLException && cause.sqlState?.let(::isUnavailableSqlState) == true)
        }

    private fun isUnavailableSqlState(sqlState: String): Boolean =
        (sqlState.startsWith(CONNECTION_EXCEPTION_CLASS) && sqlState != PROTOCOL_VIOLATION) ||
            sqlState in UNAVAILABLE_SQL_STATES

    private companion object {
        // 장애 전환이 얼마나 걸릴지 서버는 모르므로 고정값이다. 다시 확인할 간격일 뿐이다.
        const val RETRY_AFTER_SECONDS = 5
        const val MAX_CAUSE_DEPTH = 10
        const val CONNECTION_EXCEPTION_CLASS = "08"

        // 08 계열이지만 연결 장애가 아니라 드라이버·프록시 버그 신호라 500 으로 둔다.
        const val PROTOCOL_VIOLATION = "08P01"

        // 58000: OpenProxy AllServersDown, 25006: Replica 로 내려간 옛 Primary 에 쓰기, 57P01~57P03: 서버 종료·기동 중
        val UNAVAILABLE_SQL_STATES = setOf("58000", "25006", "57P01", "57P02", "57P03")
    }
}
