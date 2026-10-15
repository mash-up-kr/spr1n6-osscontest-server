/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.document

import com.jayway.jsonpath.JsonPath
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
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DocumentApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc
    @Autowired
    private lateinit var em: EntityManager
    @Autowired
    private lateinit var objectStorage: ObjectStorage
    @Autowired
    private lateinit var s3: S3Client
    @Autowired
    private lateinit var storageProperties: StorageProperties

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

        // 트리거가 등록한 Job 의 등록 계약과 추적 ID 확인
        val job = latestJob((version["document_id"] as Number).toLong())
        assertEquals("INDEX", job["job_type"])
        assertEquals("PENDING", job["status"])
        assertEquals((version["id"] as Number).toLong(), (job["document_version_id"] as Number).toLong())
        assertEquals(0, (job["attempt_count"] as Number).toInt())
        assertEquals(0L, (job["execution_epoch"] as Number).toLong())
        assertNotNull(job["trace_id"], "SET LOCAL 로 넘긴 trace_id 가 있어야 한다")
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

        // 버전마다 INDEX Job 이 하나씩, 등록 순서대로 생긴다.
        val all = jobs(documentId)
        assertEquals(2, all.size)
        assertEquals(listOf("INDEX", "INDEX"), all.map { it["job_type"] })
        assertEquals(
            all.map { (it["enqueue_seq"] as Number).toLong() }.sorted(),
            all.map { (it["enqueue_seq"] as Number).toLong() },
        )
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

    @Test
    fun `같은 테넌트의 문서 목록과 상세를 조회한다`() {
        val documentId = createDocument("목록 본문".toByteArray())

        mockMvc.get("/api/v1/documents") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(documentId) }
            jsonPath("$.items[0].title") { value("a") }
            jsonPath("$.items[0].latestVersionIndexingStatus") { value("PENDING") }
            jsonPath("$.nextCursor") { value(null) }
        }

        val sameTenantUser = AppUser(tenant = tenant, email = "b@example.com", name = "김민서")
            .also { em.persist(it) }
        em.flush()

        mockMvc.get("/api/v1/documents/$documentId") {
            header(USER_ID_HEADER, sameTenantUser.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(documentId) }
            jsonPath("$.latestUploadVersionNo") { value(1) }
        }
    }

    @Test
    fun `문서 목록 limit 이 범위를 벗어나면 400 이다`() {
        mockMvc.get("/api/v1/documents") {
            param("limit", "101")
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
            jsonPath("$.message") { value(org.hamcrest.Matchers.containsString("limit은 100 이하여야 합니다.")) }
        }
    }

    @Test
    fun `문서 목록 제목 검색은 와일드카드를 글자로 취급한다`() {
        val literalDocumentId = createDocument("literal".toByteArray(), title = "100% 달성")
        createDocument("wildcard".toByteArray(), title = "100점 달성")

        mockMvc.get("/api/v1/documents") {
            param("q", "100%")
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(literalDocumentId) }
            jsonPath("$.items[0].title") { value("100% 달성") }
        }
    }

    @Test
    fun `버전 목록과 상세를 조회한다`() {
        val documentId = createDocument("버전1".toByteArray())

        mockMvc.multipart("/api/v1/documents/$documentId/versions") {
            file(MockMultipartFile("file", "b.txt", "text/plain", "버전2".toByteArray()))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect { status { isAccepted() } }
        uploadedKeys += latestVersion()["source_object_key"] as String

        mockMvc.get("/api/v1/documents/$documentId/versions") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].versionNo") { value(2) }
            jsonPath("$.items[0].originalFilename") { value("b.txt") }
            jsonPath("$.items[0].indexing.status") { value("PENDING") }
            jsonPath("$.items[1].versionNo") { value(1) }
        }

        mockMvc.get("/api/v1/documents/$documentId/versions/2") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.versionNo") { value(2) }
            jsonPath("$.mimeType") { value("text/plain") }
            jsonPath("$.sourceMetadata") { value(null) }
            jsonPath("$.extractedMetadata") { value(null) }
        }
    }

    @Test
    fun `최신 Job 이 RETRY_WAIT 인 버전의 목록과 상세에 단계·시도 횟수·오류·재시도 시각이 나온다`() {
        val documentId = createDocument("버전 인덱싱 상세".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()
        updateLatestJob(
            versionId = versionId,
            status = "RETRY_WAIT",
            phase = "EMBEDDING",
            attemptCount = 2,
            lastErrorMessage = "임베딩 응답이 없습니다",
            lastErrorCode = "EMBEDDING_TIMEOUT",
            nextRetryAt = Instant.parse("2026-10-14T03:00:00Z"),
        )

        mockMvc.get("/api/v1/documents/$documentId/versions") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].indexing.status") { value("RETRY_WAIT") }
            jsonPath("$.items[0].indexing.phase") { value("EMBEDDING") }
            jsonPath("$.items[0].indexing.attemptCount") { value(2) }
            jsonPath("$.items[0].indexing.lastErrorMessage") { value("임베딩 응답이 없습니다") }
            jsonPath("$.items[0].indexing.lastErrorCode") { value("EMBEDDING_TIMEOUT") }
            jsonPath("$.items[0].indexing.nextRetryAt") { value("2026-10-14T03:00:00Z") }
        }

        mockMvc.get("/api/v1/documents/$documentId/versions/1") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.indexing.status") { value("RETRY_WAIT") }
            jsonPath("$.indexing.phase") { value("EMBEDDING") }
            jsonPath("$.indexing.attemptCount") { value(2) }
            jsonPath("$.indexing.lastErrorMessage") { value("임베딩 응답이 없습니다") }
            jsonPath("$.indexing.lastErrorCode") { value("EMBEDDING_TIMEOUT") }
            jsonPath("$.indexing.nextRetryAt") { value("2026-10-14T03:00:00Z") }
        }
    }

    @Test
    fun `버전 목록과 상세의 인덱싱 상태에 청크 수가 나온다`() {
        val documentId = createDocument("청크 수".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()
        em.createNativeQuery("UPDATE document_version SET indexed_at = now(), chunk_count = 3 WHERE id = :versionId")
            .setParameter("versionId", versionId)
            .executeUpdate()
        updateLatestJob(versionId = versionId, status = "COMPLETED", attemptCount = 1)

        mockMvc.get("/api/v1/documents/$documentId/versions") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].indexing.status") { value("COMPLETED") }
            jsonPath("$.items[0].indexing.chunkCount") { value(3) }
        }

        mockMvc.get("/api/v1/documents/$documentId/versions/1") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.indexing.chunkCount") { value(3) }
        }
    }

    @Test
    fun `같은 내용의 이전 버전이 있으면 버전 목록과 상세가 가장 이른 버전 번호를 알려 준다`() {
        val documentId = createDocument("같은 내용".toByteArray())
        addVersion(documentId, "같은 내용".toByteArray())
        addVersion(documentId, "다른 내용".toByteArray())

        mockMvc.get("/api/v1/documents/$documentId/versions") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].versionNo") { value(3) }
            jsonPath("$.items[0].duplicateOfVersionNo") { value(null) }
            jsonPath("$.items[1].versionNo") { value(2) }
            jsonPath("$.items[1].duplicateOfVersionNo") { value(1) }
            jsonPath("$.items[2].versionNo") { value(1) }
            jsonPath("$.items[2].duplicateOfVersionNo") { value(null) }
        }

        mockMvc.get("/api/v1/documents/$documentId/versions/2") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.duplicateOfVersionNo") { value(1) }
        }

        mockMvc.get("/api/v1/documents/$documentId/versions/1") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.duplicateOfVersionNo") { value(null) }
        }
    }

    @Test
    fun `버전 목록 limit 이 범위를 벗어나면 400 이다`() {
        val documentId = createDocument("버전".toByteArray())

        mockMvc.get("/api/v1/documents/$documentId/versions") {
            param("limit", "0")
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
            jsonPath("$.message") { value(org.hamcrest.Matchers.containsString("limit은 1 이상이어야 합니다.")) }
        }
    }

    @Test
    fun `원본 파일을 다운로드한다`() {
        val content = "다운로드 본문".toByteArray()
        val documentId = createDocument(content)

        val response = mockMvc.get("/api/v1/documents/$documentId/versions/1/content") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            header { string("Content-Type", "application/pdf") }
            header {
                string(
                    org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                    org.hamcrest.Matchers.containsString("filename*=")
                )
            }
        }.andReturn().response

        assertContentEquals(content, response.contentAsByteArray)
    }

    @Test
    fun `소유자는 제목을 변경한다`() {
        val documentId = createDocument("제목 변경".toByteArray())

        mockMvc.patch("/api/v1/documents/$documentId") {
            header(USER_ID_HEADER, user.id.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"새 제목"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(documentId) }
            jsonPath("$.title") { value("새 제목") }
        }
    }

    @Test
    fun `소유자가 아니면 제목을 변경할 수 없다`() {
        val documentId = createDocument("제목 변경".toByteArray())
        val sameTenantUser = AppUser(tenant = tenant, email = "c@example.com", name = "박민서")
            .also { em.persist(it) }
        em.flush()

        mockMvc.patch("/api/v1/documents/$documentId") {
            header(USER_ID_HEADER, sameTenantUser.id.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"새 제목"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `소유자는 문서를 삭제한다`() {
        val documentId = createDocument("삭제".toByteArray())

        mockMvc.delete("/api/v1/documents/$documentId") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isNoContent() }
        }

        em.flush()
        em.clear()

        mockMvc.get("/api/v1/documents/$documentId") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("DOCUMENT_NOT_FOUND") }
        }

        val deletedAt = em.createNativeQuery("SELECT deleted_at FROM document WHERE id = :documentId")
            .setParameter("documentId", documentId)
            .singleResult
        assertNotNull(deletedAt)

        val deleteJob = latestJob(documentId)
        assertEquals("DELETE", deleteJob["job_type"])
        assertEquals("PENDING", deleteJob["status"])
        assertEquals(null, deleteJob["document_version_id"])
    }

    @Test
    fun `인덱싱 완료 버전을 검색 대상으로 지정한다`() {
        val documentId = createDocument("검색 대상".toByteArray())
        val versionId = latestVersion()["id"] as Number

        mockMvc.put("/api/v1/documents/$documentId/searchable-version") {
            header(USER_ID_HEADER, user.id.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"versionNo":1}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SEARCHABLE_VERSION_NOT_READY") }
        }

        em.createNativeQuery("UPDATE document_version SET indexed_at = now(), chunk_count = 3 WHERE id = :versionId")
            .setParameter("versionId", versionId)
            .executeUpdate()
        em.flush()
        em.clear()

        mockMvc.put("/api/v1/documents/$documentId/searchable-version") {
            header(USER_ID_HEADER, user.id.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"versionNo":1}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.searchableVersionNo") { value(1) }
        }
    }

    @Test
    fun `인덱싱 상태를 조회한다`() {
        val documentId = createDocument("인덱싱 상태".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()

        updateLatestJob(
            versionId = versionId,
            status = "FAILED",
            phase = "CHUNKING",
            attemptCount = 2,
            lastErrorMessage = "파싱 실패",
        )

        mockMvc.get("/api/v1/documents/$documentId/versions/1/indexing") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.versionNo") { value(1) }
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.phase") { value("CHUNKING") }
            jsonPath("$.attemptCount") { value(2) }
            jsonPath("$.lastErrorMessage") { value("파싱 실패") }
        }
    }

    @Test
    fun `재시도 대기 중이면 다음 재시도 시각과 오류 코드를 보여 준다`() {
        val documentId = createDocument("재시도 대기".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()
        val nextRetryAt = Instant.parse("2026-10-14T03:00:00Z")

        updateLatestJob(
            versionId = versionId,
            status = "RETRY_WAIT",
            attemptCount = 2,
            lastErrorMessage = "임베딩 응답이 없습니다",
            lastErrorCode = "EMBEDDING_TIMEOUT",
            nextRetryAt = nextRetryAt,
        )

        mockMvc.get("/api/v1/documents/$documentId/versions/1/indexing") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("RETRY_WAIT") }
            jsonPath("$.nextRetryAt") { value("2026-10-14T03:00:00Z") }
            jsonPath("$.lastErrorCode") { value("EMBEDDING_TIMEOUT") }
        }
    }

    @Test
    fun `재시도 대기가 아니면 남아 있는 재시도 시각을 보여 주지 않는다`() {
        val documentId = createDocument("재시도 시각 잔존".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()

        updateLatestJob(
            versionId = versionId,
            status = "FAILED",
            attemptCount = 5,
            lastErrorMessage = "파싱 실패",
            lastErrorCode = "PARSE_FAILED",
            nextRetryAt = Instant.parse("2026-10-14T03:00:00Z"),
        )

        mockMvc.get("/api/v1/documents/$documentId/versions/1/indexing") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.nextRetryAt") { value(null) }
            jsonPath("$.lastErrorCode") { value("PARSE_FAILED") }
        }
    }

    @Test
    fun `같은 버전에 Job 이 여럿이면 목록과 상세 모두 가장 늦게 등록된 Job 을 보여 준다`() {
        val documentId = createDocument("동시 Job".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()

        // 첫 Job 을 실패로 끝내고, created_at 이 같은 두 번째 Job 을 완료 상태로 넣는다.
        // 재시도가 연달아 일어나면 실제로 생길 수 있는 상태다.
        updateLatestJob(versionId = versionId, status = "FAILED", attemptCount = 5)
        insertSiblingJob(documentId, versionId, status = "COMPLETED")

        val detailBody = mockMvc.get("/api/v1/documents/$documentId/versions/1/indexing") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString

        val listBody = mockMvc.get("/api/v1/documents") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(documentId) }
        }.andReturn().response.contentAsString

        // created_at 이 같아도 enqueue_seq 로 고르므로 두 경로가 같은 Job 을 고른다.
        assertEquals("COMPLETED", JsonPath.read<String>(detailBody, "$.status"))
        assertEquals("COMPLETED", JsonPath.read<String>(listBody, "$.items[0].latestVersionIndexingStatus"))
    }

    @Test
    fun `실패한 인덱싱 작업을 재시도한다`() {
        val documentId = createDocument("재인덱싱".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()
        updateLatestJob(versionId = versionId, status = "FAILED", attemptCount = 3)

        mockMvc.post("/api/v1/documents/$documentId/versions/1/indexing/retry") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.versionNo") { value(1) }
            jsonPath("$.indexing.status") { value("PENDING") }
        }

        mockMvc.get("/api/v1/documents/$documentId/versions/1/indexing") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.versionNo") { value(1) }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.attemptCount") { value(0) }
        }

        mockMvc.get("/api/v1/documents/$documentId/versions/1") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.indexing.status") { value("PENDING") }
        }

        mockMvc.get("/api/v1/documents/$documentId/versions") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].indexing.status") { value("PENDING") }
        }

        mockMvc.get("/api/v1/documents") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].latestVersionIndexingStatus") { value("PENDING") }
        }

        mockMvc.post("/api/v1/documents/$documentId/versions/1/indexing/retry") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INDEXING_RETRY_NOT_ALLOWED") }
            jsonPath("$.message") { value("현재 실패 상태인 인덱싱 작업만 재시도할 수 있습니다.") }
        }

        // 재시도는 새 Job 을 등록하고 이전 실패 Job 은 이력으로 남는다.
        val all = jobs(documentId)
        assertEquals(listOf("FAILED", "PENDING"), all.map { it["status"] })
        assertEquals(listOf("INDEX", "INDEX"), all.map { it["job_type"] })
        assertEquals(0, (latestJob(documentId)["attempt_count"] as Number).toInt())
    }

    @Test
    fun `재시도한 Job 이 다시 실패하면 또 재시도할 수 있다`() {
        val documentId = createDocument("재시도 반복".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()
        updateLatestJob(versionId = versionId, status = "FAILED", attemptCount = 5)

        mockMvc.post("/api/v1/documents/$documentId/versions/1/indexing/retry") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect { status { isAccepted() } }

        updateLatestJob(versionId = versionId, status = "FAILED", attemptCount = 5)

        mockMvc.post("/api/v1/documents/$documentId/versions/1/indexing/retry") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.indexing.status") { value("PENDING") }
        }

        assertEquals(listOf("FAILED", "FAILED", "PENDING"), jobs(documentId).map { it["status"] })
    }

    @Test
    fun `완료된 버전은 재시도할 수 없다`() {
        val documentId = createDocument("완료".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()
        updateLatestJob(versionId = versionId, status = "COMPLETED", attemptCount = 1)

        mockMvc.post("/api/v1/documents/$documentId/versions/1/indexing/retry") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INDEXING_RETRY_NOT_ALLOWED") }
        }
    }

    @Test
    fun `실패 상태가 아니면 인덱싱을 재시도할 수 없다`() {
        val documentId = createDocument("재인덱싱 불가".toByteArray())
        val versionId = (latestVersion()["id"] as Number).toLong()
        updateLatestJob(versionId = versionId, status = "PROCESSING", attemptCount = 1)

        mockMvc.post("/api/v1/documents/$documentId/versions/1/indexing/retry") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INDEXING_RETRY_NOT_ALLOWED") }
        }
    }

    @Test
    fun `문서를 만들면 소유자 ADMIN 과 테넌트 READ 권한이 생긴다`() {
        val documentId = createDocument("권한 본문".toByteArray())

        mockMvc.get("/api/v1/documents/$documentId/permissions") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].principalType") { value("USER") }
            jsonPath("$.items[0].principalId") { value(user.id.toString()) }
            jsonPath("$.items[0].permission") { value("ADMIN") }
            jsonPath("$.items[1].principalType") { value("TENANT") }
            jsonPath("$.items[1].principalId") { value(tenant.id.toString()) }
            jsonPath("$.items[1].permission") { value("READ") }
        }
    }

    @Test
    fun `소유자의 권한은 회수하지 못한다`() {
        val documentId = createDocument("권한 본문".toByteArray())

        mockMvc.delete("/api/v1/documents/$documentId/permissions/USER/${user.id}") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("OWNER_PERMISSION_NOT_REVOCABLE") }
        }
    }

    @Test
    fun `테넌트 READ 를 회수하면 다른 사용자가 읽지 못한다`() {
        val documentId = createDocument("권한 본문".toByteArray())
        val sameTenantUser = AppUser(tenant = tenant, email = "d@example.com", name = "최민서")
            .also { em.persist(it) }
        em.flush()

        mockMvc.delete("/api/v1/documents/$documentId/permissions/TENANT/${tenant.id}") {
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/documents/$documentId") {
            header(USER_ID_HEADER, sameTenantUser.id.toString())
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    private fun createDocument(content: ByteArray, title: String? = null): Long {
        mockMvc.multipart("/api/v1/documents") {
            file(MockMultipartFile("file", "a.pdf", "application/pdf", content))
            title?.let { param("title", it) }
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect { status { isAccepted() } }

        val version = latestVersion()
        uploadedKeys += version["source_object_key"] as String

        return (version["document_id"] as Number).toLong()
    }

    private fun addVersion(documentId: Long, content: ByteArray) {
        mockMvc.multipart("/api/v1/documents/$documentId/versions") {
            file(MockMultipartFile("file", "a.pdf", "application/pdf", content))
            header(USER_ID_HEADER, user.id.toString())
        }.andExpect { status { isAccepted() } }

        uploadedKeys += latestVersion()["source_object_key"] as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun latestVersion(): Map<String, Any?> =
        em.createNativeQuery(
            "SELECT id, document_id, source_object_key, convert_from(app_decrypt(original_filename), 'UTF8') AS original_filename, " +
                    "mime_type, content_hash FROM document_version ORDER BY id DESC LIMIT 1",
            Map::class.java,
        ).singleResult as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun latestJob(documentId: Long): Map<String, Any?> =
        em.createNativeQuery(
            "SELECT id, job_type, status, document_version_id, attempt_count, execution_epoch, trace_id, enqueue_seq " +
                    "FROM indexing_job WHERE document_id = :documentId ORDER BY enqueue_seq DESC LIMIT 1",
            Map::class.java,
        ).setParameter("documentId", documentId).singleResult as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun jobs(documentId: Long): List<Map<String, Any?>> =
        em.createNativeQuery(
            "SELECT id, job_type, status, document_version_id, enqueue_seq " +
                    "FROM indexing_job WHERE document_id = :documentId ORDER BY enqueue_seq",
            Map::class.java,
        ).setParameter("documentId", documentId).resultList as List<Map<String, Any?>>

    /** 트리거가 등록한 버전의 최신 Job 을 Worker 가 처리한 것처럼 바꾼다. */
    private fun updateLatestJob(
        versionId: Long,
        status: String,
        phase: String? = null,
        attemptCount: Int,
        lastErrorMessage: String? = null,
        lastErrorCode: String? = null,
        nextRetryAt: Instant? = null,
    ) {
        // ck_indexing_job_lease: PROCESSING 만 worker_id·lease_until 을 가진다.
        val leased = status == "PROCESSING"
        em.createNativeQuery(
            """
            UPDATE indexing_job
            SET status = :status,
                phase = :phase,
                attempt_count = :attemptCount,
                last_error_message = :lastErrorMessage,
                last_error_code = :lastErrorCode,
                next_retry_at = :nextRetryAt,
                worker_id = :workerId,
                lease_until = CASE WHEN :leased THEN now() + interval '5 minutes' END,
                updated_at = now()
            WHERE id = (
                SELECT id FROM indexing_job
                WHERE document_version_id = :versionId AND job_type = 'INDEX'
                ORDER BY enqueue_seq DESC LIMIT 1
            )
            """.trimIndent(),
        )
            .setParameter("versionId", versionId)
            .setParameter("status", status)
            .setParameter("phase", phase)
            .setParameter("attemptCount", attemptCount)
            .setParameter("lastErrorMessage", lastErrorMessage)
            .setParameter("lastErrorCode", lastErrorCode)
            .setParameter("nextRetryAt", nextRetryAt)
            .setParameter("workerId", if (leased) "test-worker" else null)
            .setParameter("leased", leased)
            .executeUpdate()
        em.flush()
        em.clear()
    }

    /** 같은 버전에 created_at 이 같은 INDEX Job 을 하나 더 넣는다. 최신 Job 선택 규칙을 검사할 때 쓴다. */
    private fun insertSiblingJob(documentId: Long, versionId: Long, status: String) {
        em.createNativeQuery(
            """
            INSERT INTO indexing_job (source_event_id, document_id, document_version_id, job_type, status,
                attempt_count, execution_epoch, created_at, updated_at)
            SELECT gen_random_uuid(), :documentId, :versionId, 'INDEX', :status, 1, 0, created_at, now()
            FROM indexing_job
            WHERE document_version_id = :versionId
            ORDER BY enqueue_seq DESC LIMIT 1
            """.trimIndent(),
        )
            .setParameter("documentId", documentId)
            .setParameter("versionId", versionId)
            .setParameter("status", status)
            .executeUpdate()
        em.flush()
        em.clear()
    }

    private fun documentTitle(): String =
        em.createNativeQuery("SELECT title FROM document ORDER BY id DESC LIMIT 1").singleResult as String
}
