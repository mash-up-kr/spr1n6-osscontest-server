/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.document.service

import java.io.InputStream

data class DocumentDownloadResult(
    val originalFilename: String,
    val mimeType: String,
    val fileSize: Long,
    val content: InputStream,
)
