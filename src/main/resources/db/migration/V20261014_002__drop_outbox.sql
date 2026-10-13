-- =============================================================================
-- outbox 제거
--
-- indexing_job 을 직접 등록하게 바뀌어(V20261014_001) outbox 를 읽거나 쓰는 곳이 없다.
-- 트리거는 소비되지 않는 PENDING 행만 쌓으므로 트리거·함수·테이블을 함께 지운다.
--
-- 남은 행은 여기서 검사하지 않는다. 적용 전에 운영 DB 에서 PENDING·PUBLISHING 행을 확인하고,
-- Job 이 없는 버전은 재업로드할지 정한다. DROP 뒤에는 다시 볼 수 없다.
-- =============================================================================

DROP TRIGGER IF EXISTS trg_document_version_outbox ON document_version;
DROP TRIGGER IF EXISTS trg_document_deleted_outbox ON document;

DROP FUNCTION IF EXISTS fn_outbox_indexing_requested();
DROP FUNCTION IF EXISTS fn_outbox_document_deleted();

-- indexing_job.source_event_id 는 이 테이블에 FK 가 없어 영향이 없다.
DROP TABLE IF EXISTS outbox_event;
