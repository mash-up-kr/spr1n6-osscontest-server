package com.osscontest.server.common.exception

open class ApiException(
    val errorCode: ErrorCode,
    message: String = errorCode.defaultMessage,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
