package com.osscontest.server.common.domain

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/** created_at만 가진 테이블의 공통 부모. */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseCreatedAtEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set
}
