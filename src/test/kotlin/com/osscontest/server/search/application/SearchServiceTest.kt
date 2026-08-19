package com.osscontest.server.search.application

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.search.config.SearchProperties
import com.osscontest.server.search.domain.SearchResultItem
import com.osscontest.server.search.infrastructure.EmbeddingClient
import com.osscontest.server.search.infrastructure.SearchChunkRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * DB/네트워크 없이 검증·clamp 로직만 확인한다. 실제 하이브리드 검색 SQL 자체(4.3절)는
 * @SpringBootTest + Postgres/pgvector 가 필요한 통합 테스트로 별도 작성해야 한다.
 */
class SearchServiceTest {

    private val user = AuthContext(userId = 1L, tenantId = 10L)
    private val properties = SearchProperties(defaultTopK = 10, maxTopK = 50, maxContextWindow = 5)

    private fun newService(
        embeddingClient: EmbeddingClient = mock {
            on { embed(any()) } doReturn listOf(0.1f)
        },
        repository: SearchChunkRepository = mock {
            on { hybridSearch(any(), any(), any(), any(), any(), any(), any()) } doReturn emptyList<SearchResultItem>()
        },
    ) = Triple(SearchService(embeddingClient, repository, properties), embeddingClient, repository)

    @Test
    fun `공백만 있는 질의는 임베딩 호출 없이 거부한다`() {
        val (service, embeddingClient, _) = newService()

        val exception = assertFailsWith<BusinessException> {
            service.search(user, SearchRequest(query = "   "))
        }

        assertEquals(ErrorCode.INVALID_QUERY, exception.errorCode)
        verify(embeddingClient, never()).embed(any())
    }

    @Test
    fun `topK가 상한을 넘으면 최대값으로 clamp 된다`() {
        val (service, _, repository) = newService()

        service.search(user, SearchRequest(query = "위약금", topK = 999))

        val topKCaptor = argumentCaptor<Int>()
        verify(repository).hybridSearch(any(), any(), any(), any(), topKCaptor.capture(), any(), any())
        assertEquals(50, topKCaptor.firstValue)
    }

    @Test
    fun `topK를 지정하지 않으면 기본값을 쓴다`() {
        val (service, _, repository) = newService()

        service.search(user, SearchRequest(query = "위약금"))

        val topKCaptor = argumentCaptor<Int>()
        verify(repository).hybridSearch(any(), any(), any(), any(), topKCaptor.capture(), any(), any())
        assertEquals(10, topKCaptor.firstValue)
    }

    @Test
    fun `contextWindow가 음수면 0으로 clamp 된다`() {
        val (service, _, repository) = newService()

        service.search(user, SearchRequest(query = "위약금", contextWindow = -3))

        val contextWindowCaptor = argumentCaptor<Int>()
        verify(repository).hybridSearch(any(), any(), any(), any(), any(), contextWindowCaptor.capture(), any())
        assertEquals(0, contextWindowCaptor.firstValue)
    }

    @Test
    fun `contextWindow가 상한을 넘으면 최대값으로 clamp 된다`() {
        val (service, _, repository) = newService()

        service.search(user, SearchRequest(query = "위약금", contextWindow = 100))

        val contextWindowCaptor = argumentCaptor<Int>()
        verify(repository).hybridSearch(any(), any(), any(), any(), any(), contextWindowCaptor.capture(), any())
        assertEquals(5, contextWindowCaptor.firstValue)
    }

    @Test
    fun `앞뒤 공백은 제거된 뒤 임베딩·검색에 쓰인다`() {
        val embeddingClient = mock<EmbeddingClient> {
            on { embed(eq("위약금")) } doReturn listOf(0.1f)
        }
        val (service, _, repository) = newService(embeddingClient = embeddingClient)

        service.search(user, SearchRequest(query = "  위약금  "))

        verify(embeddingClient).embed("위약금")
        val queryTextCaptor = argumentCaptor<String>()
        verify(repository).hybridSearch(any(), any(), queryTextCaptor.capture(), any(), any(), any(), any())
        assertEquals("위약금", queryTextCaptor.firstValue)
    }

    @Test
    fun `테넌트·유저 컨텍스트는 요청 바디가 아니라 인증 컨텍스트에서 온다`() {
        val (service, _, repository) = newService()

        service.search(user, SearchRequest(query = "위약금"))

        verify(repository).hybridSearch(
            tenantId = eq(10L),
            userId = eq(1L),
            queryText = any(),
            queryEmbedding = any(),
            topK = any(),
            contextWindow = any(),
            efSearch = any(),
        )
    }

    @Test
    fun `efSearch를 지정하지 않으면 기본값 100을 쓴다`() {
        val (service, _, repository) = newService()

        service.search(user, SearchRequest(query = "위약금"))

        val efSearchCaptor = argumentCaptor<Int>()
        verify(repository).hybridSearch(any(), any(), any(), any(), any(), any(), efSearchCaptor.capture())
        assertEquals(100, efSearchCaptor.firstValue)
    }

    @Test
    fun `efSearch를 지정하면 그대로 전달된다`() {
        val (service, _, repository) = newService()

        service.search(user, SearchRequest(query = "위약금", efSearch = 200))

        val efSearchCaptor = argumentCaptor<Int>()
        verify(repository).hybridSearch(any(), any(), any(), any(), any(), any(), efSearchCaptor.capture())
        assertEquals(200, efSearchCaptor.firstValue)
    }
}
