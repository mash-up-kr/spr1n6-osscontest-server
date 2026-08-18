package com.osscontest.server.common.domain

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.LastModifiedDate
import java.time.Instant

/** created_at 과 updated_at 을 함께 가진 테이블의 공통 부모. */
@MappedSuperclass
abstract class BaseTimeEntity : BaseCreatedAtEntity() {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set
}
