package com.osscontest.server.search.infrastructure

/**
 * pgvector-java 의존성을 추가하지 않고, pgvector 가 텍스트 입력으로 받아들이는
 * `[0.1,0.2,...]` 리터럴을 만들어 `CAST(:queryEmbedding AS vector)`에 그대로 바인딩한다.
 */
fun List<Float>.toPgVectorLiteral(): String =
    joinToString(prefix = "[", postfix = "]") { it.toString() }
