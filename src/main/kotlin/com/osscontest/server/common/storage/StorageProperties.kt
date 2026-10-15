/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.common.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties(prefix = "storage")
data class StorageProperties(
    val endpoint: URI,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
)
