package com.osscontest.server

import com.osscontest.server.document.domain.*
import com.osscontest.server.indexing.domain.IndexingJob
import com.osscontest.server.outbox.domain.OutboxEvent
import com.osscontest.server.outbox.domain.OutboxEventType
import com.osscontest.server.outbox.domain.OutboxStatus
import com.osscontest.server.tenant.domain.Tenant
import com.osscontest.server.user.domain.AppUser
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 엔티티 매핑이 스키마와 맞물리는지 확인한다. validate 가 못 잡는 부분이 있어 왕복까지 본다. */
@SpringBootTest
@Transactional
class EntityMappingTest {

    @Autowired
    private lateinit var em: EntityManager

    @Test
    fun `문서와 버전을 저장하고 다시 읽는다`() {
        val tenant = Tenant(name = "mashup").also { em.persist(it) }

        val user = AppUser(
            tenant = tenant,
            email = "a@example.com",
            name = "장민서",
        ).also { em.persist(it) }

        val userWithSameName = AppUser(
            tenant = tenant,
            email = "b@example.com",
            name = "장민서",
        ).also { em.persist(it) }

        val document = Document(
            tenant = tenant,
            ownerPrincipalId = "user-1",
            title = "설계 문서",
        ).also { em.persist(it) }

        val version = DocumentVersion(
            document = document,
            versionNo = 1,
            sourceObjectKey = "tenants/1/documents/1/versions/1/a.pdf",
            originalFilename = "사업계획서_v1.pdf",
            mimeType = "application/pdf",
            fileSize = 20480,
            contentHash = "sha256:abc",
            createdByPrincipalId = "user-1",
        ).also {
            it.sourceMetadata = mapOf("uploadedFrom" to "web", "pageCount" to 12)
            em.persist(it)
        }

        val scope = DocumentAccessScope(
            document = document,
            principalType = PrincipalType.USER,
            principalId = "user-2",
            permission = Permission.READ,
            grantedByPrincipalId = "user-1",
        ).also { em.persist(it) }

        em.flush()
        em.clear()

        val reloadedDocument = em.find(Document::class.java, document.id)
        assertNotNull(reloadedDocument.createdAt, "createdAt 이 채워져야 한다")
        assertNotNull(reloadedDocument.updatedAt, "updatedAt 이 채워져야 한다")
        assertEquals(0, reloadedDocument.latestUploadVersionNo)
        assertEquals(0, reloadedDocument.latestEmbeddingVersionNo)
        assertNull(reloadedDocument.searchableVersionId)

        val reloadedVersion = em.find(DocumentVersion::class.java, version.id)
        assertEquals(reloadedVersion.versionNo, reloadedVersion.embeddingVersionNo)
        assertEquals("web", reloadedVersion.sourceMetadata?.get("uploadedFrom"))
        assertEquals(12, reloadedVersion.sourceMetadata?.get("pageCount"))

        // 암호화 컬럼
        assertEquals("사업계획서_v1.pdf", reloadedVersion.originalFilename)
        assertEquals("장민서", em.find(AppUser::class.java, user.id).name)
        assertEquals("장민서", em.find(AppUser::class.java, userWithSameName.id).name)

        // 실제 컬럼에는 평문 UTF-8 바이트열이 아닌 암호문이 저장된다.
        val storedNameHex = em.createNativeQuery(
            "SELECT encode(name, 'hex') FROM app_user WHERE id = :id",
        ).setParameter("id", user.id).singleResult as String
        val samePlaintextNameHex = em.createNativeQuery(
            "SELECT encode(name, 'hex') FROM app_user WHERE id = :id",
        ).setParameter("id", userWithSameName.id).singleResult as String
        val storedFilenameHex = em.createNativeQuery(
            "SELECT encode(original_filename, 'hex') FROM document_version WHERE id = :id",
        ).setParameter("id", version.id).singleResult as String

        assertNotEquals(
            "장민서".toUtf8Hex(),
            storedNameHex,
            "사용자 이름은 평문이 아닌 암호문으로 저장돼야 한다",
        )
        assertNotEquals(
            "사업계획서_v1.pdf".toUtf8Hex(),
            storedFilenameHex,
            "원본 파일명은 평문이 아닌 암호문으로 저장돼야 한다",
        )
        assertNotEquals(
            storedNameHex,
            samePlaintextNameHex,
            "같은 평문도 서로 다른 암호문으로 저장돼야 한다",
        )

        val reloadedScope = em.find(DocumentAccessScope::class.java, scope.id)
        assertEquals(PrincipalType.USER, reloadedScope.principalType)
        assertEquals(Permission.READ, reloadedScope.permission)
        assertEquals(tenant.id, reloadedScope.tenantId, "tenant_id 는 document 에서 파생된다")
    }

    @Test
    fun `버전을 저장하면 트리거가 Outbox 행을 만든다`() {
        val tenant = Tenant(name = "mashup").also { em.persist(it) }
        val document = Document(
            tenant = tenant,
            ownerPrincipalId = "user-1",
            title = "설계 문서",
        ).also { em.persist(it) }

        val version = DocumentVersion(
            document = document,
            versionNo = 1,
            sourceObjectKey = "tenants/1/documents/1/versions/1/a.pdf",
            originalFilename = "사업계획서_v1.pdf",
            mimeType = "application/pdf",
            fileSize = 20480,
            contentHash = "sha256:abc",
            createdByPrincipalId = "user-1",
        ).also { em.persist(it) }

        em.flush()
        em.clear()

        val events = em.createQuery(
            "SELECT e FROM OutboxEvent e WHERE e.documentVersionId = :id",
            OutboxEvent::class.java,
        ).setParameter("id", version.id).resultList

        assertEquals(1, events.size, "트리거가 Outbox 행을 만들어야 한다")

        val event = events.first()
        assertEquals(OutboxEventType.INDEXING_REQUESTED, event.eventType)
        assertEquals(OutboxStatus.PENDING, event.status)
        assertEquals(document.id, event.documentId)
        assertEquals(tenant.id, event.tenantId)
        assertNull(event.retryOfEventId)
        assertEquals(1, (event.payload["versionNo"] as Number).toInt())
        assertEquals("application/pdf", event.payload["mimeType"])
    }

    @Test
    fun `인덱싱 Job 을 읽는다`() {
        val tenant = Tenant(name = "mashup").also { em.persist(it) }
        val document = Document(
            tenant = tenant,
            ownerPrincipalId = "user-1",
            title = "설계 문서",
        ).also { em.persist(it) }

        val version = DocumentVersion(
            document = document,
            versionNo = 1,
            sourceObjectKey = "tenants/1/documents/1/versions/1/a.pdf",
            originalFilename = "사업계획서_v1.pdf",
            mimeType = "application/pdf",
            fileSize = 20480,
            contentHash = "sha256:abc",
            createdByPrincipalId = "user-1",
        ).also { em.persist(it) }
        em.flush()

        val job = IndexingJob(
            sourceEventId = UUID.randomUUID(),
            documentId = document.id!!,
            documentVersionId = version.id!!,
            status = IndexingStatus.FAILED,
        ).also {
            it.attemptCount = 3
            it.phase = "EMBEDDING"
            it.lastErrorCode = "EMBEDDING_TIMEOUT"
            it.lastErrorMessage = "임베딩 응답이 없습니다"
            it.completedAt = Instant.now()
            em.persist(it)
        }

        em.flush()
        em.clear()

        val reloaded = em.find(IndexingJob::class.java, job.id)
        assertEquals(IndexingStatus.FAILED, reloaded.status)
        assertEquals(3, reloaded.attemptCount)
        assertEquals("EMBEDDING", reloaded.phase)
        assertEquals("EMBEDDING_TIMEOUT", reloaded.lastErrorCode)
        assertEquals("임베딩 응답이 없습니다", reloaded.lastErrorMessage)
        assertNotNull(reloaded.completedAt)
        assertNotNull(reloaded.updatedAt)
    }

    @Test
    fun `재인덱싱 이벤트를 애플리케이션이 직접 넣는다`() {
        val tenant = Tenant(name = "mashup").also { em.persist(it) }
        val document = Document(
            tenant = tenant,
            ownerPrincipalId = "user-1",
            title = "설계 문서",
        ).also { em.persist(it) }

        val version = DocumentVersion(
            document = document,
            versionNo = 1,
            sourceObjectKey = "tenants/1/documents/1/versions/1/a.pdf",
            originalFilename = "사업계획서_v1.pdf",
            mimeType = "application/pdf",
            fileSize = 20480,
            contentHash = "sha256:abc",
            createdByPrincipalId = "user-1",
        ).also { em.persist(it) }

        em.flush()

        val origin = em.createQuery(
            "SELECT e FROM OutboxEvent e WHERE e.documentVersionId = :id",
            OutboxEvent::class.java,
        ).setParameter("id", version.id).singleResult

        val retry = OutboxEvent(
            id = UUID.randomUUID(),
            tenantId = tenant.id!!,
            documentId = document.id!!,
            eventType = OutboxEventType.INDEXING_REQUESTED,
            payload = mapOf("tenantId" to tenant.id, "versionNo" to version.versionNo),
        ).also {
            it.documentVersionId = version.id
            it.retryOfEventId = origin.id
            em.persist(it)
        }

        em.flush()
        em.clear()

        val reloaded = em.find(OutboxEvent::class.java, retry.id)
        assertEquals(origin.id, reloaded.retryOfEventId)
        assertEquals(OutboxStatus.PENDING, reloaded.status)
        assertEquals(1, reloaded.eventSchemaVersion)
        assertNotNull(reloaded.nextAttemptAt)
    }

    private fun String.toUtf8Hex(): String =
        toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
}
