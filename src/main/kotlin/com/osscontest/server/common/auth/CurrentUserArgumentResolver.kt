package com.osscontest.server.common.auth

import com.osscontest.server.common.exception.ApiException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.user.domain.AppUserRepository
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.stereotype.Component

private const val USER_ID_HEADER = "X-User-Id"

/**
 * `X-User-Id` 헤더 -> [com.osscontest.server.user.domain.AppUser] 조회 -> tenant 획득.
 * docs/api-design.md 3장 공통 규약("인증 컨텍스트는 X-User-Id 헤더로 받습니다")의 구현체.
 */
@Component
class CurrentUserArgumentResolver(
    private val appUserRepository: AppUserRepository,
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java) &&
            parameter.parameterType == AuthenticatedUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): AuthenticatedUser {
        val userId = webRequest.getHeader(USER_ID_HEADER)?.toLongOrNull()
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED)

        val tenantId = appUserRepository.findTenantIdById(userId)
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED)

        return AuthenticatedUser(userId = userId, tenantId = tenantId)
    }
}
