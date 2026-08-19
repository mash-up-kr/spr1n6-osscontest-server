package com.osscontest.server.common.web

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.user.repository.AppUserRepository
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/** AuthContext 를 파라미터로 선언한 핸들러에 인증 정보 주입. */
@Component
class AuthContextArgumentResolver(
    private val appUserRepository: AppUserRepository,
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == AuthContext::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): AuthContext {
        val userId = webRequest.getHeader(USER_ID_HEADER)?.toLongOrNull()
            ?: throw BusinessException(ErrorCode.UNAUTHENTICATED)
        val tenantId = appUserRepository.findTenantIdById(userId)
            ?: throw BusinessException(ErrorCode.UNAUTHENTICATED)

        return AuthContext(userId = userId, tenantId = tenantId)
    }

    companion object {
        const val USER_ID_HEADER = "X-User-Id"
    }
}
