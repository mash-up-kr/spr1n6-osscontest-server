-- =============================================================================
-- 키워드 검색용 형태소 분석 토큰 컬럼
--
-- ts_rank_cd는 IDF가 없어 흔한 단어를 못 눌러준다. BM25(IDF 포함) 랭킹을
-- 애플리케이션 레이어(Kotlin, Nori 토크나이저)에서 계산하기로 하고, 그 입력이
-- 될 정규화 토큰을 이 컬럼에 저장한다. 원문 content를 직접 형태소 분석해
-- 인덱싱하면 Postgres가 함수를 호출할 수 있어야 하는데, Nori는 JVM 애플리케이션
-- 안에서만 실행되어 Postgres가 못 부르므로 결과를 컬럼으로 미리 계산해 둬야 한다.
--
-- 적재 시점에 Worker가 채운다. 기존 행은 별도 백필 배치가 채우기 전까지 NULL이라
-- nullable로 둔다.
-- =============================================================================

ALTER TABLE document_chunk
    ADD COLUMN content_tokens TEXT;

COMMENT ON COLUMN document_chunk.content_tokens IS
    'Nori 형태소 분석으로 정규화한 검색용 토큰(공백 구분). 적재 시점에 Worker가 채우고, NULL이면 키워드 후보 조회에서 제외된다.';

-- 키워드 후보 회수(recall) 전용 인덱스. 최종 랭킹은 애플리케이션에서 BM25로
-- 계산하므로, 이 인덱스는 "질의 토큰 중 하나라도 겹치는 청크"를 넓게 가져오는
-- 용도로만 쓴다. idx_document_chunk_content_tsv(원문 기준)는 그대로 남겨 둔다 —
-- BM25 경로가 검증되기 전까지 기존 키워드 검색의 대체 경로로 계속 쓸 수 있어야 한다.
CREATE INDEX idx_document_chunk_content_tokens_tsv
    ON document_chunk USING GIN (to_tsvector('simple', content_tokens));
