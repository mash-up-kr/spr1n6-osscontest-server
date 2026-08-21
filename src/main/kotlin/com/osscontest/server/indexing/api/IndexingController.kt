package com.osscontest.server.indexing.api

import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.indexing.service.IndexingService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** URL 은 문서 아래에 있지만 다루는 도메인이 인덱싱이라 컨트롤러를 따로 둔다. */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/versions/{versionNo}/indexing")
class IndexingController(
    private val indexingService: IndexingService,
) {

    @GetMapping
    fun getStatus(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @PathVariable versionNo: Long,
    ): IndexingStatusResponse =
        indexingService.getStatus(authContext, documentId, versionNo)

    @PostMapping("/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retry(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @PathVariable versionNo: Long,
    ): IndexingRetryResponse =
        indexingService.retry(authContext, documentId, versionNo)
}
