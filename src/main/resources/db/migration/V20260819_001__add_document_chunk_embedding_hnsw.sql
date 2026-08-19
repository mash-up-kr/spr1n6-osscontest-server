-- =============================================================================
-- document_chunk.embedding 벡터 유사도 인덱스 (HNSW)
--
-- V20260812_001의 document_chunk CREATE TABLE과 의도적으로 분리한 별도 마이그레이션이다.
-- CREATE INDEX CONCURRENTLY는 트랜잭션 블록 안에서 실행할 수 없는데, Flyway는 마이그레이션
-- 파일 하나를 트랜잭션 하나로 감싸 실행하므로 테이블 생성과 같은 파일에 넣으면 그 자리에서 실패한다.
-- =============================================================================

CREATE INDEX CONCURRENTLY idx_document_chunk_embedding_hnsw
ON document_chunk
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
