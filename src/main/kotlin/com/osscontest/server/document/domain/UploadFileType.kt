package com.osscontest.server.document.domain

/**
 * 업로드를 허용하는 형식. 클라이언트가 보내는 MIME 은 환경별로 흔들릴 수 있어 확장자로 판정한다.
 * 저장되는 mimeType 은 여기서 정한 값으로 정규화한다.
 */
enum class UploadFileType(val mimeType: String, vararg val extensions: String) {
    PDF("application/pdf", "pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    MARKDOWN("text/markdown", "md", "markdown"),
    HWP("application/x-hwp", "hwp"),
    TEXT("text/plain", "txt"),
    ;

    companion object {
        fun ofExtension(extension: String): UploadFileType? =
            entries.firstOrNull { type -> type.extensions.any { it.equals(extension, ignoreCase = true) } }
    }
}
