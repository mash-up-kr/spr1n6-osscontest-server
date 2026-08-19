package com.osscontest.server.document.api

import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.document.service.DocumentService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/documents")
class DocumentController(
    private val documentService: DocumentService,
) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createDocument(
        authContext: AuthContext,
        @RequestPart file: MultipartFile,
        @Valid @ModelAttribute request: CreateDocumentRequest,
    ): DocumentUploadResponse =
        documentService.createDocument(authContext, file, request.title)

    @PostMapping("/{documentId}/versions", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun addVersion(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @RequestPart file: MultipartFile,
    ): DocumentUploadResponse =
        documentService.addVersion(authContext, documentId, file)
}
