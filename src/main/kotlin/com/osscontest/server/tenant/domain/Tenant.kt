package com.osscontest.server.tenant.domain

import com.osscontest.server.common.domain.BaseCreatedAtEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tenant")
class Tenant(

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

) : BaseCreatedAtEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null
}
