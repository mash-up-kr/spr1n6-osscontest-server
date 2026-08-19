package com.osscontest.server.document

import com.osscontest.server.common.web.AuthContextArgumentResolver.Companion.USER_ID_HEADER
import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentAccessScope
import com.osscontest.server.document.domain.Permission
import com.osscontest.server.document.domain.PrincipalType
import com.osscontest.server.tenant.domain.Tenant
import com.osscontest.server.user.domain.AppUser
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DocumentPermissionApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var em: EntityManager

    private lateinit var tenant: Tenant
    private lateinit var owner: AppUser
    private lateinit var member: AppUser
    private lateinit var document: Document

    @BeforeEach
    fun setUp() {
        tenant = Tenant(name = "mashup").also { em.persist(it) }
        owner = AppUser(tenant = tenant, email = "owner@example.com", name = "소유자").also { em.persist(it) }
        member = AppUser(tenant = tenant, email = "member@example.com", name = "팀원").also { em.persist(it) }
        document = Document(tenant = tenant, ownerPrincipalId = owner.id.toString(), title = "사업계획서")
            .also { em.persist(it) }
        em.flush()
    }

    @Test
    fun `소유자가 사용자에게 권한을 부여한다`() {
        grant(owner, PrincipalType.USER, member.id.toString(), Permission.WRITE)
            .andExpect {
                status { isOk() }
                jsonPath("$.principalType") { value("USER") }
                jsonPath("$.principalId") { value(member.id.toString()) }
                jsonPath("$.permission") { value("WRITE") }
            }
    }

    @Test
    fun `같은 대상에 다시 부여하면 권한만 바뀐다`() {
        grant(owner, PrincipalType.USER, member.id.toString(), Permission.READ).andExpect { status { isOk() } }
        grant(owner, PrincipalType.USER, member.id.toString(), Permission.ADMIN).andExpect {
            status { isOk() }
            jsonPath("$.permission") { value("ADMIN") }
        }

        mockMvc.get("/api/v1/documents/${document.id}/permissions") {
            header(USER_ID_HEADER, owner.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].permission") { value("ADMIN") }
        }
    }

    @Test
    fun `테넌트 단위로도 권한을 부여한다`() {
        grant(owner, PrincipalType.TENANT, tenant.id.toString(), Permission.READ)
            .andExpect {
                status { isOk() }
                jsonPath("$.principalType") { value("TENANT") }
            }
    }

    @Test
    fun `ADMIN 권한을 받은 사용자도 권한을 관리한다`() {
        persistScope(member, Permission.ADMIN)

        grant(member, PrincipalType.TENANT, tenant.id.toString(), Permission.READ)
            .andExpect { status { isOk() } }
    }

    @Test
    fun `WRITE 권한만 있으면 권한을 관리하지 못한다`() {
        persistScope(member, Permission.WRITE)

        mockMvc.get("/api/v1/documents/${document.id}/permissions") {
            header(USER_ID_HEADER, member.id.toString())
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `권한이 없는 사용자는 목록을 보지 못한다`() {
        mockMvc.get("/api/v1/documents/${document.id}/permissions") {
            header(USER_ID_HEADER, member.id.toString())
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `다른 테넌트의 문서는 찾지 못한다`() {
        val otherTenant = Tenant(name = "other").also { em.persist(it) }
        val outsider = AppUser(tenant = otherTenant, email = "out@example.com", name = "외부인")
            .also { em.persist(it) }
        em.flush()

        mockMvc.get("/api/v1/documents/${document.id}/permissions") {
            header(USER_ID_HEADER, outsider.id.toString())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("DOCUMENT_NOT_FOUND") }
        }
    }

    @Test
    fun `같은 테넌트에 없는 사용자에게는 부여하지 못한다`() {
        val otherTenant = Tenant(name = "other").also { em.persist(it) }
        val outsider = AppUser(tenant = otherTenant, email = "out@example.com", name = "외부인")
            .also { em.persist(it) }
        em.flush()

        grant(owner, PrincipalType.USER, outsider.id.toString(), Permission.READ)
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("PRINCIPAL_NOT_FOUND") }
            }
    }

    @Test
    fun `권한을 회수한다`() {
        persistScope(member, Permission.READ)

        mockMvc.delete("/api/v1/documents/${document.id}/permissions/USER/${member.id}") {
            header(USER_ID_HEADER, owner.id.toString())
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/documents/${document.id}/permissions") {
            header(USER_ID_HEADER, owner.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(0) }
        }
    }

    @Test
    fun `부여되지 않은 권한은 회수하지 못한다`() {
        mockMvc.delete("/api/v1/documents/${document.id}/permissions/USER/${member.id}") {
            header(USER_ID_HEADER, owner.id.toString())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("PERMISSION_NOT_FOUND") }
        }
    }

    private fun grant(actor: AppUser, principalType: PrincipalType, principalId: String, permission: Permission) =
        mockMvc.put("/api/v1/documents/${document.id}/permissions") {
            header(USER_ID_HEADER, actor.id.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """
                { "principalType": "$principalType", "principalId": "$principalId", "permission": "$permission" }
            """.trimIndent()
        }

    private fun persistScope(target: AppUser, permission: Permission) {
        DocumentAccessScope(
            document = document,
            principalType = PrincipalType.USER,
            principalId = target.id.toString(),
            permission = permission,
            grantedByPrincipalId = owner.id.toString(),
        ).also { em.persist(it) }
        em.flush()
    }
}
