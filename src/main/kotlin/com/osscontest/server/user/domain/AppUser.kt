package com.osscontest.server.user.domain

import com.osscontest.server.common.domain.BaseCreatedAtEntity
import com.osscontest.server.tenant.domain.Tenant
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "app_user")
class AppUser(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    var tenant: Tenant,

    @Column(name = "email", nullable = false, length = 255)
    var email: String,

    /**
     * 암호화 대상 컬럼이므로 VARBINARY 로 선언. 암복호화는 app_encrypt / app_decrypt 함수 시그니처 사용.
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @ColumnTransformer(read = "app_decrypt(name)", write = "app_encrypt(?)")
    @Column(name = "name", nullable = false)
    var name: String,

) : BaseCreatedAtEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null
}
