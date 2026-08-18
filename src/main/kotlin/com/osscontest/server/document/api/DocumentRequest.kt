package com.osscontest.server.document.api

import jakarta.validation.constraints.Size

data class CreateDocumentRequest(
    @field:Size(max = 255, message = "title은 255자 이하여야 합니다.")
    val title: String? = null,
)
