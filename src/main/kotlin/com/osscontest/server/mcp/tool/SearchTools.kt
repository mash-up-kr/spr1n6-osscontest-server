package com.osscontest.server.mcp.tool

import com.osscontest.server.common.exception.BusinessException
import com.osscontest.server.common.exception.ErrorCode
import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.common.web.PageResponse
import com.osscontest.server.document.api.DocumentSummary
import com.osscontest.server.document.api.ListDocumentsRequest
import com.osscontest.server.document.service.DocumentService
import com.osscontest.server.mcp.config.SearchMcpTransportConfig
import com.osscontest.server.search.application.SearchRequest
import com.osscontest.server.search.application.SearchService
import com.osscontest.server.search.domain.SearchResultItem
import com.osscontest.server.user.repository.AppUserRepository
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext
import org.springframework.stereotype.Component

/**
 * mcp 구현체
 *
 * McpSyncRequestContext 파라미터는 @McpToolParam 이 없어 LLM 에게 노출되는 tool 인자가
 * 아니라 프레임워크가 주입하는 값이다(SearchMcpTransportConfig 가 채운 X-Search-User-Id
 * 헤더를 여기서 읽는다). 헤더가 비어 있으면 즉시 인증 실패로 응답한다.
 */
@Component
class SearchTools(
    private val searchService: SearchService,
    private val documentService: DocumentService,
    private val appUserRepository: AppUserRepository,
) {

    @McpTool(
        name = "search_documents",
        description = "질의어로 문서를 하이브리드 검색한다 (벡터 유사도 + 키워드 매칭 결합). " +
            "매칭된 청크와 앞뒤 문맥, 소속 문서 정보를 함께 반환한다.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    )
    fun searchDocuments(
        @McpToolParam(description = "검색 질의문", required = true)
        query: String,
        @McpToolParam(description = "반환할 최대 결과 수. 기본 10, 최대 50", required = false)
        topK: Int?,
        @McpToolParam(description = "매칭 청크 앞뒤로 함께 반환할 청크 수. 기본 0(미포함)", required = false)
        contextWindow: Int?,
        @McpToolParam(
            description = "검색 정확도와 속도의 트레이드오프(HNSW 탐색 폭). 기본 100, 1~500 범위. " +
                "클수록 정확하지만 느려지고 작을수록 빠르지만 놓치는 결과가 늘어난다. " +
                "'정확하게'/'꼼꼼하게' 요청이면 크게(예: 200~500), " +
                "'빠르게' 요청이면 작게(예: 10~50) 설정한다.",
            required = false,
        )
        efSearch: Int?,
        requestContext: McpSyncRequestContext,
    ): List<SearchResultItem> =
        searchService.search(resolveAuthContext(requestContext), SearchRequest(query, topK, contextWindow, efSearch))

    @McpTool(
        name = "list_documents",
        description = "현재 tenant에 속한 문서 목록을 조회한다. 커서 기반 페이지네이션을 지원한다.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    )
    fun listDocuments(
        @McpToolParam(description = "반환할 최대 문서 수. 기본 20, 최대 100", required = false)
        limit: Int?,
        @McpToolParam(description = "이전 응답의 nextCursor 값. 다음 페이지를 이어서 조회할 때 지정한다", required = false)
        cursor: String?,
        @McpToolParam(description = "문서 제목 검색어. 지정하면 제목에 포함된 문서만 반환한다", required = false)
        q: String?,
        @McpToolParam(
            description = "최신 버전의 인덱싱 상태로 필터링. PENDING/PROCESSING/RETRY_WAIT/COMPLETED/FAILED 중 하나",
            required = false,
        )
        indexingStatus: String?,
        @McpToolParam(description = "검색 가능한(인덱싱 완료된) 버전이 지정돼 있는 문서만 필터링할지 여부", required = false)
        searchable: Boolean?,
        requestContext: McpSyncRequestContext,
    ): PageResponse<DocumentSummary> =
        documentService.listDocuments(
            resolveAuthContext(requestContext),
            ListDocumentsRequest(
                limit = limit ?: 20,
                cursor = cursor,
                q = q,
                indexingStatus = indexingStatus,
                searchable = searchable,
            ),
        )

    @McpTool(
        name = "get_document",
        description = "document_id로 검색 없이 문서 상세 정보를 직접 조회한다.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    )
    fun getDocument(
        @McpToolParam(description = "문서 식별자", required = true)
        documentId: Long,
        requestContext: McpSyncRequestContext,
    ): DocumentSummary = documentService.getDocument(resolveAuthContext(requestContext), documentId)

    /**
     * MCP 는 디스패처서블릿 파이프라인과 별개로 RouterFunction 로 동작하므로
     * AuthContextArgumentResolver 와 같은 조회를 리졸버 없이 직접 수행한다.
     * */
    private fun resolveAuthContext(requestContext: McpSyncRequestContext): AuthContext {
        val headerUserId = requestContext.transportContext().get(SearchMcpTransportConfig.SEARCH_USER_ID_KEY) as? String
        val userId = headerUserId?.toLongOrNull()
            ?: throw BusinessException(
                ErrorCode.UNAUTHENTICATED,
                "X-Search-User-Id 헤더가 없거나 숫자가 아닙니다. 클라이언트 설정의 headers를 확인하세요.",
            )
        val tenantId = appUserRepository.findTenantIdById(userId)
            ?: throw BusinessException(ErrorCode.UNAUTHENTICATED)
        return AuthContext(userId = userId, tenantId = tenantId)
    }
}
