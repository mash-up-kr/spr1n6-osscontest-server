/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.indexing.domain

/** INDEX 는 버전을 임베딩하고, DELETE 는 삭제된 문서의 청크를 정리한다. */
enum class JobType {
    INDEX,
    DELETE,
}
