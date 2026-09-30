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
        description = SEARCH_DOCUMENTS_DESCRIPTION,
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
            description = "결과를 재정렬 모델(Cohere Rerank)로 한 번 더 다듬을지 여부. 기본 false. " +
                "정확도가 중요한 요청('정확하게'/'꼼꼼하게' 찾아줘)이면 true로 설정한다 — 대신 지연시간이 늘어난다.",
            required = false,
        )
        rerank: Boolean?,
        requestContext: McpSyncRequestContext,
    ): List<SearchResultItem> =
        searchService.search(resolveAuthContext(requestContext), SearchRequest(query, topK, contextWindow, rerank))

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

    companion object {
        private const val SEARCH_DOCUMENTS_DESCRIPTION =
            "질의어로 문서를 하이브리드 검색한다 (벡터 유사도 + 키워드 매칭 결합). " +
                "매칭된 청크와 앞뒤 문맥, 소속 문서 정보를 함께 반환한다. " +
                "이 tool은 청크만 반환하며 답변 합성은 호출자(LLM)의 책임이다.\n\n" +
                "문서 검색 결과 합성 규칙:\n\n" +
                "1. 근거 범위\n" +
                "- 답변은 반환된 청크(content, contextBefore, contextAfter)에 실제로 적힌 내용만 근거로 한다.\n" +
                "- 청크에 없는 내용은 추측하거나 일반 지식으로 채우지 않는다. 정보가 부족하면 " +
                "\"검색된 문서에서 관련 내용을 찾지 못했습니다\"라고 명시하고, 아는 척 답하지 않는다.\n" +
                "- contextBefore/contextAfter는 매칭된 청크(content)의 맥락을 보완하는 용도로만 쓰고, " +
                "그 자체가 질문과 직접 관련 없다면 답변에 끌어오지 않는다.\n\n" +
                "2. 여러 청크 종합\n" +
                "- score가 높은 청크를 우선하되, 낮은 score라도 질문에 더 직접적으로 답하는 내용이면 우선한다.\n" +
                "- 서로 다른 청크의 내용이 상충하면 둘 다 언급하고 어느 쪽 출처가 더 최신/구체적인지 밝힌다. " +
                "임의로 한쪽만 골라 답하지 않는다.\n" +
                "- 같은 내용을 반복하는 청크는 하나로 합쳐서 답하고, 청크 원문을 그대로 나열하지 않는다 " +
                "(요약·재구성해서 질문에 맞게 답한다).\n\n" +
                "3. 출처 표기\n" +
                "- 답변에 사용한 내용마다 title과 pageFrom-pageTo(있으면) 또는 sectionPath를 함께 밝힌다. " +
                "예: \"...라고 되어 있습니다 (『검색 설계 문서』, p.4).\"\n" +
                "- 여러 문서를 종합했다면 각 근거가 어느 문서에서 왔는지 구분해서 표기한다.\n\n" +
                "4. 검색 재시도\n" +
                "- 첫 검색 결과의 score가 전반적으로 낮거나 질문과 무관해 보이면, 질의어를 바꿔 " +
                "(동의어·다른 표현으로) 한 번 더 search_documents를 호출해본다.\n" +
                "- 무작정 topK만 키우기보다, 질문을 더 구체적인 키워드/다른 자연어 표현으로 바꿔 재검색하는 걸 우선한다.\n" +
                "- 2회 재시도까지도 관련 청크가 없으면 더 캐지 말고 \"관련 문서를 찾지 못했다\"고 답한다.\n\n" +
                "5. 답변 형식\n" +
                "- 청크 원문을 그대로 붙여넣지 말고, 질문에 맞게 재구성한 문장으로 답한다.\n" +
                "- 질문이 한국어면 한국어로, 영어면 영어로 답한다.\n" +
                "- 사용자가 원문 그대로를 요청한 경우에만 예외적으로 인용(따옴표) 형태로 원문을 보여준다."
    }
}
