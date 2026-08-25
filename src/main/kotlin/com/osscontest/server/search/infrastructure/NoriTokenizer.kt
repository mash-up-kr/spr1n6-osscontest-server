package com.osscontest.server.search.infrastructure

import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.springframework.stereotype.Component

/**
 * 질의어를 청크 적재 시점(Worker)과 같은 방식으로 토큰화한다. 같은 형태소 분석기를
 * 써야 질의 토큰과 document_chunk.content_tokens가 같은 정규화 규칙을 공유해
 * GIN 인덱스 매칭과 BM25 TF 계산이 맞아떨어진다. Worker 저장소와 별도 배포 단위라
 * 로직은 동일하게 각자 유지한다.
 */
@Component
class NoriTokenizer {
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        KoreanAnalyzer().use { analyzer ->
            analyzer.tokenStream("content", text).use { stream ->
                val termAttr = stream.addAttribute(CharTermAttribute::class.java)
                stream.reset()
                while (stream.incrementToken()) {
                    tokens.add(termAttr.toString())
                }
                stream.end()
            }
        }
        return tokens
    }
}
