package com.osscontest.server.user.repository

import com.osscontest.server.user.domain.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AppUserRepository : JpaRepository<AppUser, Long> {

    /** 사용하지 않는 컬럼을 포함한 엔티티 전체 로딩 방지. */
    @Query("SELECT u.tenant.id FROM AppUser u WHERE u.id = :id")
    fun findTenantIdById(id: Long): Long?
}
