package com.osscontest.server.document

import com.osscontest.server.common.storage.ObjectStorage
import com.osscontest.server.common.storage.StorageProperties
import com.osscontest.server.common.web.AuthContextArgumentResolver.Companion.USER_ID_HEADER
import com.osscontest.server.document.domain.Document
import com.osscontest.server.tenant.domain.Tenant
import com.osscontest.server.user.domain.AppUser
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DocumentApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var em: EntityManager
    @Autowired private lateinit var objectStorage: ObjectStorage
    @Autowired private lateinit var s3: S3Client
    @Autowired private lateinit var storageProperties: StorageProperties

    private lateinit var tenant: Tenant
    private lateinit var user: AppUser
    private val uploadedKeys = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        tenant = Tenant(name = "mashup").also { em.persist(it) }
        user = AppUser(tenant = tenant, email = "a@example.com", name = "장민서").also { em.persist(it) }
        em.flush()
    }

    @AfterEach
    fun cleanUp() {
        uploadedKeys.forEach { key ->
            s3.deleteObject(DeleteObjectRequest.builder().bucket(storageProperties.bucket).key(key).build())
        }
    }

    @Test
    fun `문서를 만들고 1번 버전을 올린다`() {
        val content = "사업계획서 본문".toByteArray()

        mockMvc.multipart("/api/v1/documents") {
            file(MockMultipartFile("file", "사업계획서.pdf", "application/pdf", content))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.versionNo") { value(1) }
            jsonPath("$.duplicateOfVersionNo") { value(null) }
            jsonPath("$.indexing.status") { value("PENDING") }
        }

        val version = latestVersion()
        val key = version["source_object_key"] as String
        uploadedKeys += key

        assertEquals("사업계획서.pdf", version["original_filename"])
        assertEquals("application/pdf", version["mime_type"])
        assertEquals("사업계획서", documentTitle())
        assertContentEquals(content, objectStorage.get(key).use { it.readBytes() })

        // 트리거가 만든 Outbox 행에 추적 ID 가 들어왔는지
        val outbox = outboxRow()
        assertEquals("INDEXING_REQUESTED", outbox["event_type"])
        assertNotNull(outbox["trace_id"], "SET LOCAL 로 넘긴 trace_id 가 있어야 한다")
    }

    @Test
    fun `title 을 주면 그대로 쓴다`() {
        mockMvc.multipart("/api/v1/documents") {
            file(MockMultipartFile("file", "a.pdf", "application/pdf", "x".toByteArray()))
            param("title", "2026 사업계획서")
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect { status { isAccepted() } }

        uploadedKeys += latestVersion()["source_object_key"] as String
        assertEquals("2026 사업계획서", documentTitle())
    }

    @Test
    fun `허용하지 않는 확장자는 415 다`() {
        mockMvc.multipart("/api/v1/documents") {
            file(MockMultipartFile("file", "a.exe", "application/octet-stream", "x".toByteArray()))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.code") { value("UNSUPPORTED_FILE_TYPE") }
        }
    }

    @Test
    fun `HWP 는 MIME 이 무엇이든 확장자로 받는다`() {
        mockMvc.multipart("/api/v1/documents") {
            file(MockMultipartFile("file", "보고서.hwp", "application/octet-stream", "x".toByteArray()))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect { status { isAccepted() } }

        val version = latestVersion()
        uploadedKeys += version["source_object_key"] as String

        assertEquals("application/x-hwp", version["mime_type"])
    }

    @Test
    fun `빈 파일은 400 이다`() {
        mockMvc.multipart("/api/v1/documents") {
            file(MockMultipartFile("file", "a.pdf", "application/pdf", ByteArray(0)))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("EMPTY_FILE") }
        }
    }

    @Test
    fun `인증 정보가 없으면 401 이다`() {
        mockMvc.multipart("/api/v1/documents") {
            file(MockMultipartFile("file", "a.pdf", "application/pdf", "x".toByteArray()))
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `새 버전을 올리면 번호가 이어지고 같은 내용이면 이전 버전을 알려 준다`() {
        val content = "같은 내용".toByteArray()
        val documentId = createDocument(content)

        mockMvc.multipart("/api/v1/documents/$documentId/versions") {
            file(MockMultipartFile("file", "a.pdf", "application/pdf", content))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.versionNo") { value(2) }
            jsonPath("$.duplicateOfVersionNo") { value(1) }
        }

        uploadedKeys += latestVersion()["source_object_key"] as String
        assertEquals(2L, em.find(Document::class.java, documentId).latestUploadVersionNo)
    }

    @Test
    fun `다른 내용이면 중복으로 보지 않는다`() {
        val documentId = createDocument("처음".toByteArray())

        mockMvc.multipart("/api/v1/documents/$documentId/versions") {
            file(MockMultipartFile("file", "a.pdf", "application/pdf", "바뀐 내용".toByteArray()))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.duplicateOfVersionNo") { value(null) }
        }

        uploadedKeys += latestVersion()["source_object_key"] as String
    }

    @Test
    fun `다른 테넌트의 문서에는 버전을 올릴 수 없다`() {
        val otherTenant = Tenant(name = "other").also { em.persist(it) }
        val otherDocument = Document(
            tenant = otherTenant,
            ownerPrincipalId = "other-1",
            title = "남의 문서",
        ).also { em.persist(it) }
        em.flush()

        mockMvc.multipart("/api/v1/documents/${otherDocument.id}/versions") {
            file(MockMultipartFile("file", "a.pdf", "application/pdf", "x".toByteArray()))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("DOCUMENT_NOT_FOUND") }
        }
    }

    private fun createDocument(content: ByteArray): Long {
        mockMvc.multipart("/api/v1/documents") {
            file(MockMultipartFile("file", "a.pdf", "application/pdf", content))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect { status { isAccepted() } }

        val version = latestVersion()
        uploadedKeys += version["source_object_key"] as String

        return (version["document_id"] as Number).toLong()
    }

    @Suppress("UNCHECKED_CAST")
    private fun latestVersion(): Map<String, Any?> =
        em.createNativeQuery(
            "SELECT id, document_id, source_object_key, convert_from(app_decrypt(original_filename), 'UTF8') AS original_filename, " +
                "mime_type, content_hash FROM document_version ORDER BY id DESC LIMIT 1",
            Map::class.java,
        ).singleResult as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun outboxRow(): Map<String, Any?> =
        em.createNativeQuery(
            "SELECT event_type, trace_id FROM outbox_event ORDER BY created_at DESC LIMIT 1",
            Map::class.java,
        ).singleResult as Map<String, Any?>

    private fun documentTitle(): String =
        em.createNativeQuery("SELECT title FROM document ORDER BY id DESC LIMIT 1").singleResult as String
}
