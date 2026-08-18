package com.osscontest.server.common.auth

/** 컨트롤러 메서드 파라미터에 붙여 [AuthenticatedUser] 를 주입받는다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser
