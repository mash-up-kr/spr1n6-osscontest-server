package com.osscontest.server.common.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 공통 웹 레이어를 검증하기 위한 시험용 엔드포인트. 실제 API 가 생기기 전까지만 쓴다. */
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
