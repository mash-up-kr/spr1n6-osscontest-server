package com.osscontest.server.search.api

import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.search.application.SearchRequest
import com.osscontest.server.search.application.SearchService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** docs/API_SPEC.md 8장. */
@RestController
@RequestMapping("/api/v1/search")
class SearchController(
    private val searchService: SearchService,
) {

    @PostMapping
    fun search(
        user: AuthContext,
        @RequestBody request: SearchRequest,
    ): SearchResponse = SearchResponse(items = searchService.search(user, request))
}
