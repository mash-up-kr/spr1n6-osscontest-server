package com.osscontest.server.indexing

import com.osscontest.server.document.domain.Document
import com.osscontest.server.document.domain.DocumentVersion
import com.osscontest.server.tenant.domain.Tenant
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals

/** DocumentApiTest·EntityMappingTest 는 클래스 전체가 @Transactional 이라 커밋·롤백 경계를 보지 못해 따로 둔다. */
@SpringBootTest
class IndexingJobEnqueueRollbackTest {

    @Autowired
    private lateinit var em: EntityManager
    @Autowired
    private lateinit var tx: TransactionTemplate

    @Test
    fun `버전 저장 트랜잭션이 롤백되면 등록한 Job 도 남지 않는다`() {
        val documentId = tx.execute { status ->
            val tenant = Tenant(name = "rollback").also { em.persist(it) }
            val document = Document(tenant = tenant, ownerPrincipalId = "user-1", title = "롤백")
                .also { em.persist(it) }
            DocumentVersion(
                document = document,
                versionNo = 1,
                sourceObjectKey = "tenants/0/documents/0/versions/1/rollback.pdf",
                originalFilename = "rollback.pdf",
                mimeType = "application/pdf",
                fileSize = 1,
                contentHash = "sha256:rollback",
                createdByPrincipalId = "user-1",
            ).also { em.persist(it) }
            em.flush()

            val inside = em.createNativeQuery("SELECT count(*) FROM indexing_job WHERE document_id = :id")
                .setParameter("id", document.id)
                .singleResult as Number
            assertEquals(1L, inside.toLong(), "트랜잭션 안에서는 트리거가 등록한 Job 이 보여야 한다")

            status.setRollbackOnly()
            document.id!!
        }!!

        val after = tx.execute {
            em.createNativeQuery("SELECT count(*) FROM indexing_job WHERE document_id = :id")
                .setParameter("id", documentId)
                .singleResult as Number
        }!!
        assertEquals(0L, after.toLong())
    }
}
