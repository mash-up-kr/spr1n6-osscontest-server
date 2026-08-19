-- =============================================================================
-- document_chunk.embedding 벡터 유사도 인덱스 (HNSW)
-- =============================================================================

CREATE INDEX idx_document_chunk_embedding_hnsw
ON document_chunk
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
