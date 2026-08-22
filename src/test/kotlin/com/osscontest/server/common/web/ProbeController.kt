package com.osscontest.server.common.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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
}

data class ProbeRequest(
    @field:NotBlank
    val title: String,
)
