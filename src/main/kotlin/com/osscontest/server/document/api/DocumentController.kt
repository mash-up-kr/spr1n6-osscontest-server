package com.osscontest.server.document.api

import com.osscontest.server.common.web.AuthContext
import com.osscontest.server.common.web.PageResponse
import com.osscontest.server.document.service.DocumentService
import jakarta.validation.Valid
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

    @GetMapping
    fun listDocuments(
        authContext: AuthContext,
        @Valid @ModelAttribute request: ListDocumentsRequest,
    ): PageResponse<DocumentSummary> =
        documentService.listDocuments(authContext, request)

    @GetMapping("/{documentId}")
    fun getDocument(
        authContext: AuthContext,
        @PathVariable documentId: Long,
    ): DocumentSummary =
        documentService.getDocument(authContext, documentId)

    @PatchMapping("/{documentId}")
    fun updateTitle(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @Valid @RequestBody request: UpdateDocumentTitleRequest,
    ): DocumentTitleResponse =
        documentService.updateTitle(authContext, documentId, request.title)

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDocument(
        authContext: AuthContext,
        @PathVariable documentId: Long,
    ) {
        documentService.deleteDocument(authContext, documentId)
    }

    @PutMapping("/{documentId}/searchable-version")
    fun updateSearchableVersion(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @Valid @RequestBody request: UpdateSearchableVersionRequest,
    ): SearchableVersionResponse =
        documentService.updateSearchableVersion(authContext, documentId, request.versionNo)

    @PostMapping("/{documentId}/versions", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun addVersion(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @RequestPart file: MultipartFile,
    ): DocumentUploadResponse =
        documentService.addVersion(authContext, documentId, file)

    @GetMapping("/{documentId}/versions")
    fun listVersions(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @Valid @ModelAttribute request: ListDocumentVersionsRequest,
    ): PageResponse<DocumentVersionSummary> =
        documentService.listVersions(authContext, documentId, request)

    @GetMapping("/{documentId}/versions/{versionNo}")
    fun getVersion(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @PathVariable versionNo: Long,
    ): DocumentVersionDetailResponse =
        documentService.getVersion(authContext, documentId, versionNo)

    @GetMapping("/{documentId}/versions/{versionNo}/content")
    fun downloadVersion(
        authContext: AuthContext,
        @PathVariable documentId: Long,
        @PathVariable versionNo: Long,
    ): ResponseEntity<InputStreamResource> {
        val file = documentService.downloadVersion(authContext, documentId, versionNo)
        val encodedFilename = URLEncoder.encode(file.originalFilename, StandardCharsets.UTF_8)
            .replace("+", "%20")

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.mimeType))
            .contentLength(file.fileSize)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''$encodedFilename",
            )
            .body(InputStreamResource(file.content))
    }
}
