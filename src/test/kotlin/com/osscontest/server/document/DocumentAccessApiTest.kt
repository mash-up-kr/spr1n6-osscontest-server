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
import org.springframework.test.web.servlet.patch
import org.springframework.transaction.annotation.Transactional

/** 부여된 권한에 따라 문서 쓰기가 허용되는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DocumentAccessApiTest {

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
    fun `소유자는 제목을 변경한다`() {
        updateTitle(owner).andExpect { status { isOk() } }
    }

    @Test
    fun `WRITE 권한을 받으면 제목을 변경한다`() {
        grantToUser(member, Permission.WRITE)

        updateTitle(member).andExpect { status { isOk() } }
    }

    @Test
    fun `ADMIN 권한을 받으면 제목을 변경한다`() {
        grantToUser(member, Permission.ADMIN)

        updateTitle(member).andExpect { status { isOk() } }
    }

    @Test
    fun `테넌트에 부여된 WRITE 권한으로도 제목을 변경한다`() {
        grantToTenant(Permission.WRITE)

        updateTitle(member).andExpect { status { isOk() } }
    }

    @Test
    fun `READ 권한만 있으면 제목을 변경하지 못한다`() {
        grantToUser(member, Permission.READ)

        updateTitle(member).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `권한이 없으면 문서를 삭제하지 못한다`() {
        mockMvc.delete("/api/v1/documents/${document.id}") {
            header(USER_ID_HEADER, member.id.toString())
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `WRITE 권한을 받으면 문서를 삭제한다`() {
        grantToUser(member, Permission.WRITE)

        mockMvc.delete("/api/v1/documents/${document.id}") {
            header(USER_ID_HEADER, member.id.toString())
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `없는 문서는 권한과 무관하게 404 다`() {
        mockMvc.patch("/api/v1/documents/999999") {
            header(USER_ID_HEADER, owner.id.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"새 제목"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("DOCUMENT_NOT_FOUND") }
        }
    }

    private fun updateTitle(actor: AppUser) =
        mockMvc.patch("/api/v1/documents/${document.id}") {
            header(USER_ID_HEADER, actor.id.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"새 제목"}"""
        }

    private fun grantToUser(target: AppUser, permission: Permission) =
        persistScope(PrincipalType.USER, target.id.toString(), permission)

    private fun grantToTenant(permission: Permission) =
        persistScope(PrincipalType.TENANT, tenant.id.toString(), permission)

    private fun persistScope(principalType: PrincipalType, principalId: String, permission: Permission) {
        DocumentAccessScope(
            document = document,
            principalType = principalType,
            principalId = principalId,
            permission = permission,
            grantedByPrincipalId = owner.id.toString(),
        ).also { em.persist(it) }
        em.flush()
    }
}
