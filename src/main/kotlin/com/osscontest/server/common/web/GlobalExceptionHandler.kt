package com.osscontest.server.common.web

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
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

/**
 * 예외를 code · message · traceId 형식의 응답으로 변환.
 *
 * - BusinessException: ErrorCode 가 정한 상태
 * - 본문 검증 실패와 파라미터 제약 위반: 400 과 위반한 항목 이름
 * - 스프링 MVC 예외: 404 · 405 · 415 등 MVC 계약 보존
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
        ErrorResponse(code = code, message = message, traceId = TraceIdFilter.currentTraceId())
}
