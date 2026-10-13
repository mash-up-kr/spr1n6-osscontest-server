-- =============================================================================
-- indexing_job 직접 등록
--
-- Relay 와 Worker 의 Kafka listener 가 제거되어 outbox_event 를 indexing_job 으로
-- 옮기는 주체가 없다. 버전 저장과 문서 삭제 트리거가 indexing_job 을 같은 트랜잭션에서 등록한다.
-- =============================================================================

-- 업로드 경로는 객체 업로드가 끝난 뒤 버전을 저장하므로, 이 트리거가 실행될 때 원문은 이미 저장소에 있다.
CREATE OR REPLACE FUNCTION fn_enqueue_index_job()
RETURNS TRIGGER AS $$
BEGIN
    -- Worker 와 같은 문서 → Job 순서로 잠근다. 업로드 경로는 이미 문서를 잠갔거나 같은 트랜잭션에서 만든 행이다.
    PERFORM 1 FROM document WHERE id = NEW.document_id FOR UPDATE;

    -- ON CONFLICT 로 삼키지 않는다. 같은 버전에 미종결 Job 이 있으면
    -- uq_indexing_job_active_version 위반으로 요청 트랜잭션을 실패시킨다.
    INSERT INTO indexing_job (
        source_event_id, document_id, document_version_id, job_type,
        status, attempt_count, execution_epoch, trace_id
    )
    VALUES (
        gen_random_uuid(), NEW.document_id, NEW.id, 'INDEX',
        'PENDING', 0, 0, current_setting('app.trace_id', true)
    );

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


CREATE TRIGGER trg_document_version_enqueue_job
    AFTER INSERT ON document_version
    FOR EACH ROW
    EXECUTE FUNCTION fn_enqueue_index_job();


CREATE OR REPLACE FUNCTION fn_enqueue_delete_job()
RETURNS TRIGGER AS $$
BEGIN
    -- UPDATE 트리거라 문서 행은 이미 이 트랜잭션이 잠갔다.
    INSERT INTO indexing_job (
        source_event_id, document_id, document_version_id, job_type,
        status, attempt_count, execution_epoch, trace_id
    )
    VALUES (
        gen_random_uuid(), NEW.id, NULL, 'DELETE',
        'PENDING', 0, 0, current_setting('app.trace_id', true)
    );

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- deleted_at 이 이미 채워진 행을 다시 UPDATE 해도 Job 이 늘지 않는다.
CREATE TRIGGER trg_document_deleted_enqueue_job
    AFTER UPDATE ON document
    FOR EACH ROW
    WHEN (OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL)
    EXECUTE FUNCTION fn_enqueue_delete_job();
