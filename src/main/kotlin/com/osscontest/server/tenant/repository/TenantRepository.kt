package com.osscontest.server.tenant.repository

import com.osscontest.server.tenant.domain.Tenant
import org.springframework.data.jpa.repository.JpaRepository

interface TenantRepository : JpaRepository<Tenant, Long>
