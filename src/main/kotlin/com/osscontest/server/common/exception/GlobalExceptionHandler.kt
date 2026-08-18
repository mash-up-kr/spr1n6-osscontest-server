package com.osscontest.server.common.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ErrorResponse> {
        val traceId = newTraceId()
        logAtAppropriateLevel(traceId, ex.errorCode.status, ex)
        return ResponseEntity.status(ex.errorCode.status)
            .body(ErrorResponse(ex.errorCode.name, ex.message ?: ex.errorCode.defaultMessage, traceId))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val traceId = newTraceId()
        val message = ex.bindingResult.fieldErrors
            .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
            .ifBlank { ErrorCode.INVALID_PARAMETER.defaultMessage }
        return ResponseEntity.status(ErrorCode.INVALID_PARAMETER.status)
            .body(ErrorResponse(ErrorCode.INVALID_PARAMETER.name, message, traceId))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        val traceId = newTraceId()
        logger.error("[{}] unhandled exception", traceId, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", traceId))
    }

    private fun logAtAppropriateLevel(traceId: String, status: HttpStatus, ex: ApiException) {
        if (status.is5xxServerError) {
            logger.error("[{}] {}", traceId, ex.message, ex)
        } else {
            logger.debug("[{}] {}", traceId, ex.message)
        }
    }

    private fun newTraceId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
}
