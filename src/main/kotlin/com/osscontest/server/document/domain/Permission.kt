package com.osscontest.server.document.domain

/** 문서 접근 권한. 상위 권한은 하위 권한을 포함한다. */
enum class Permission(val level: Int) {
    READ(1),
    WRITE(2),
    ADMIN(3),
    ;

    fun satisfies(required: Permission): Boolean = level >= required.level
}
