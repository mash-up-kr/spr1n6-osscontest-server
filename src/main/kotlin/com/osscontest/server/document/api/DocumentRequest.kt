package com.osscontest.server.document.api

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateDocumentRequest(
    @field:Size(max = 255, message = "title은 255자 이하여야 합니다.")
    val title: String? = null,
)

data class UpdateDocumentTitleRequest(
    @field:NotBlank(message = "title은 비어 있을 수 없습니다.")
    @field:Size(max = 255, message = "title은 255자 이하여야 합니다.")
    val title: String,
)

data class ListDocumentsRequest(
    @field:Min(value = 1, message = "limit은 1 이상이어야 합니다.")
    @field:Max(value = 100, message = "limit은 100 이하여야 합니다.")
    val limit: Int = 20,
    val cursor: String? = null,
    val q: String? = null,
    val indexingStatus: String? = null,
    val searchable: Boolean? = null,
)

data class ListDocumentVersionsRequest(
    @field:Min(value = 1, message = "limit은 1 이상이어야 합니다.")
    @field:Max(value = 100, message = "limit은 100 이하여야 합니다.")
    val limit: Int = 20,
    val cursor: String? = null,
)
