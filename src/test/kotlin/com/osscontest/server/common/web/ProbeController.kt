package com.osscontest.server.common.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/**
 * 공통 웹 계층만 격리해 검증하기 위한 전용 엔드포인트.
 *
 * 실제 컨트롤러로 대신하면 도메인 로직이 섞여 실패 원인을 가린다.
 * `/test/param` 은 제약을 건 @RequestParam 을 쓰는 유일한 자리라
 * GlobalExceptionHandler.handleHandlerMethodValidationException 의 유일한 호출 경로다.
 */
@RestController
class ProbeController {

    @GetMapping("/test/auth")
    fun auth(authContext: AuthContext): AuthContext = authContext

    @GetMapping("/test/param")
    fun param(@RequestParam @Min(1) limit: Int): Int = limit

    @PostMapping("/test/validate")
    fun validate(@Valid @RequestBody request: ProbeRequest): ProbeRequest = request

    /** JdbcTemplate 경로처럼 SQLException 을 DataAccessException 으로 감싸 던진다. */
    @GetMapping("/test/db-error")
    fun dbError(@RequestParam sqlState: String): Nothing =
        throw DataAccessResourceFailureException("probe", SQLException("probe", sqlState))

    /** JPA 트랜잭션 시작 경로처럼 Hikari 연결 대기 초과를 감싸 던진다. */
    @GetMapping("/test/db-connection-timeout")
    fun dbConnectionTimeout(): Nothing =
        throw CannotCreateTransactionException("probe", SQLTransientConnectionException("probe"))
}

data class ProbeRequest(
    @field:NotBlank
    val title: String,
)
