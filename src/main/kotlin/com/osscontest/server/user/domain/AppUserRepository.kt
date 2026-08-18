package com.osscontest.server.user.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AppUserRepository : JpaRepository<AppUser, Long> {

    /**
     * tenant 를 직접 프로젝션한다. `open-in-view: false` 라 [AppUser.tenant] 를
     * 지연 로딩으로 건드리면 트랜잭션 밖에서 LazyInitializationException 이 난다.
     */
    @Query("select u.tenant.id from AppUser u where u.id = :id")
    fun findTenantIdById(id: Long): Long?
}
