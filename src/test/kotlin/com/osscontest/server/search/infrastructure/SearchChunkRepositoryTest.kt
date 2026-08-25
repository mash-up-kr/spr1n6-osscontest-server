package com.osscontest.server.search.infrastructure

import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentAccessScope
import com.osscontest.server.document.domain.DocumentVersion
import com.osscontest.server.document.domain.Permission
import com.osscontest.server.document.domain.PrincipalType
import com.osscontest.server.search.domain.SearchOptions
import com.osscontest.server.tenant.domain.Tenant
import com.osscontest.server.user.domain.AppUser
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 4.3절 RRF 쿼리가 실제 Postgres/pgvector에서 맞게 동작하는지 확인한다.
 * 벡터 순위 계산은 임베딩 모델 없이 결정론적으로 테스트하기 어려워, 모든 청크에 같은
 * 더미 임베딩을 줘서 벡터 쪽 순위를 무력화하고 키워드 매칭·권한 필터·문맥 확장만 검증한다.
 */
@SpringBootTest
@Transactional
class SearchChunkRepositoryTest {

    @Autowired
    private lateinit var em: EntityManager

    @Autowired
    private lateinit var repository: SearchChunkRepository

    @Autowired
    private lateinit var noriTokenizer: NoriTokenizer

    // 모든 청크가 공유하는 더미 벡터. 값 자체는 의미 없고, 질의 임베딩과 동일해 벡터 거리가
    // 전부 0으로 묶이게 해서 RRF 결과가 키워드 매칭에 의해 결정되도록 만든다.
    private val dummyEmbedding = List(1536) { if (it == 0) 1.0f else 0.0f }

    @Test
    fun `키워드로 매칭된 청크가 문맥과 함께 반환된다`() {
        val tenant = Tenant(name = "mashup").also { em.persist(it) }
        val user = AppUser(tenant = tenant, email = "a@example.com", name = "테스터").also { em.persist(it) }
        val document = newDocument(tenant, "위약금 안내문")
        val version = newVersion(document)
        document.searchableVersionId = version.id
        grantRead(document, user.id!!)

        insertChunk(version, document, chunkNo = 1, content = "일반 문단입니다")
        insertChunk(version, document, chunkNo = 2, content = "위약금은 계약금의 10%를 넘지 않는다")
        insertChunk(version, document, chunkNo = 3, content = "다른 일반 문단입니다")

        em.flush()
        em.clear()

        val results = repository.hybridSearch(
            tenantId = tenant.id!!,
            userId = user.id!!,
            queryText = "위약금",
            queryEmbedding = dummyEmbedding,
            options = SearchOptions(topK = 10, contextWindow = 1, efSearch = 100),
        )

        val hit = results.find { it.content.contains("위약금") }
        assertTrue(hit != null, "위약금 청크가 검색돼야 한다: $results")
        checkNotNull(hit)
        assertEquals(listOf("일반 문단입니다"), hit.contextBefore)
        assertEquals(listOf("다른 일반 문단입니다"), hit.contextAfter)
        assertEquals("위약금 안내문", hit.title)
    }

    @Test
    fun `contextWindow가 0이면 문맥을 채우지 않는다`() {
        val tenant = Tenant(name = "mashup").also { em.persist(it) }
        val user = AppUser(tenant = tenant, email = "a@example.com", name = "테스터").also { em.persist(it) }
        val document = newDocument(tenant, "위약금 안내문")
        val version = newVersion(document)
        document.searchableVersionId = version.id
        grantRead(document, user.id!!)

        insertChunk(version, document, chunkNo = 1, content = "일반 문단입니다")
        insertChunk(version, document, chunkNo = 2, content = "위약금은 계약금의 10%를 넘지 않는다")

        em.flush()
        em.clear()

        val results = repository.hybridSearch(
            tenantId = tenant.id!!,
            userId = user.id!!,
            queryText = "위약금",
            queryEmbedding = dummyEmbedding,
            options = SearchOptions(topK = 10, contextWindow = 0, efSearch = 100),
        )

        val hit = results.find { it.content.contains("위약금") }
        assertTrue(hit != null)
        checkNotNull(hit)
        assertTrue(hit.contextBefore.isEmpty())
        assertTrue(hit.contextAfter.isEmpty())
    }

    @Test
    fun `권한 없는 문서의 청크는 검색되지 않는다`() {
        val tenant = Tenant(name = "mashup").also { em.persist(it) }
        val user = AppUser(tenant = tenant, email = "a@example.com", name = "테스터").also { em.persist(it) }

        // 권한을 부여하지 않은 문서
        val forbiddenDocument = newDocument(tenant, "비공개 문서")
        val forbiddenVersion = newVersion(forbiddenDocument)
        forbiddenDocument.searchableVersionId = forbiddenVersion.id
        insertChunk(forbiddenVersion, forbiddenDocument, chunkNo = 1, content = "위약금 관련 비공개 내용")

        em.flush()
        em.clear()

        val results = repository.hybridSearch(
            tenantId = tenant.id!!,
            userId = user.id!!,
            queryText = "위약금",
            queryEmbedding = dummyEmbedding,
            options = SearchOptions(topK = 10, contextWindow = 0, efSearch = 100),
        )

        assertFalse(results.any { it.content.contains("비공개") }, "권한 없는 문서 청크가 새어 나가면 안 된다")
    }

    @Test
    fun `다른 테넌트의 청크는 검색되지 않는다`() {
        val tenantA = Tenant(name = "tenant-a").also { em.persist(it) }
        val tenantB = Tenant(name = "tenant-b").also { em.persist(it) }
        val userA = AppUser(tenant = tenantA, email = "a@example.com", name = "A테스터").also { em.persist(it) }

        val documentB = newDocument(tenantB, "B테넌트 문서")
        val versionB = newVersion(documentB)
        documentB.searchableVersionId = versionB.id
        // tenant B 문서인데 principalId만 우연히 겹치는 상황을 만들어도 tenant_id 필터로 막혀야 한다.
        grantRead(documentB, userA.id!!)
        insertChunk(versionB, documentB, chunkNo = 1, content = "위약금 B테넌트 내용", tenantId = tenantB.id!!)

        em.flush()
        em.clear()

        val results = repository.hybridSearch(
            tenantId = tenantA.id!!,
            userId = userA.id!!,
            queryText = "위약금",
            queryEmbedding = dummyEmbedding,
            options = SearchOptions(topK = 10, contextWindow = 0, efSearch = 100),
        )

        assertFalse(results.any { it.content.contains("B테넌트") }, "다른 테넌트 청크가 새어 나가면 안 된다")
    }

    private fun newDocument(tenant: Tenant, title: String): Document =
        Document(tenant = tenant, ownerPrincipalId = "system", title = title).also { em.persist(it) }

    private fun newVersion(document: Document): DocumentVersion =
        DocumentVersion(
            document = document,
            versionNo = 1,
            sourceObjectKey = "tenants/x/documents/x/v1.pdf",
            originalFilename = "x.pdf",
            mimeType = "application/pdf",
            fileSize = 1024,
            contentHash = "sha256:test",
            createdByPrincipalId = "system",
        ).also {
            it.indexedAt = Instant.now()
            em.persist(it)
        }

    private fun grantRead(document: Document, userId: Long) {
        em.persist(
            DocumentAccessScope(
                document = document,
                principalType = PrincipalType.USER,
                principalId = userId.toString(),
                permission = Permission.READ,
                grantedByPrincipalId = "system",
            ),
        )
    }

    private fun insertChunk(
        version: DocumentVersion,
        document: Document,
        chunkNo: Int,
        content: String,
        tenantId: Long = document.tenant.id!!,
    ) {
        em.createNativeQuery(
            """
            INSERT INTO document_chunk
                (tenant_id, document_version_id, document_id, chunk_no, content, content_tokens,
                 content_hash, embedding, embedded_at)
            VALUES
                (:tenantId, :versionId, :documentId, :chunkNo, :content, :contentTokens,
                 :contentHash, CAST(:embedding AS vector), now())
            """.trimIndent(),
        )
            .setParameter("tenantId", tenantId)
            .setParameter("versionId", version.id)
            .setParameter("documentId", document.id)
            .setParameter("chunkNo", chunkNo)
            .setParameter("content", content)
            // 실제 Worker 적재 계약과 같은 방식(NoriTokenizer)으로 토큰을 채워야
            // 키워드 후보 회수(GIN, content_tokens 기준)가 이 테스트 데이터에서도 매칭된다.
            .setParameter("contentTokens", noriTokenizer.tokenize(content).joinToString(" "))
            .setParameter("contentHash", "sha256:${content.hashCode()}")
            .setParameter("embedding", dummyEmbedding.toPgVectorLiteral())
            .executeUpdate()
    }
}
