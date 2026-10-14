-- Worker 계약: 문서 → Job 잠금, 만료 전이라도 PROCESSING 소유권을 해제하면
-- 이전 Worker 의 heartbeat/결과 쓰기가 거절된다. attempt/epoch 는 다음 claim 만 증가시킨다.
-- 기존 HA 환경의 기동/승격 시각 연동은 docs/INDEXING_JOB_RECOVERY.md 를 참고한다.

DO $$
BEGIN
    IF pg_is_in_recovery() OR current_setting('transaction_read_only')::boolean
        OR current_setting('default_transaction_read_only')::boolean THEN
        RAISE EXCEPTION 'Apply recovery migrations only on a writable Primary';
    END IF;
END $$;

CREATE TABLE public.indexing_recovery_primary (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    postmaster_started_at TIMESTAMPTZ NOT NULL,
    wal_timeline TEXT NOT NULL CHECK (wal_timeline ~ '^[0-9A-F]{8}$'),
    writable_since TIMESTAMPTZ NOT NULL
);
REVOKE ALL ON TABLE public.indexing_recovery_primary FROM PUBLIC;

-- Patroni on_start/on_restart/on_role_change 가 실행 노드의 로컬 DB 에 호출한다.
-- WAL 파일명의 timeline 은 현재 쓰기 timeline 이다. checkpoint 의 이전 timeline 을 쓰지 않는다.
-- 같은 기동/timeline 의 중복 콜백은 30초 유예를 다시 시작하지 않는다.
CREATE PROCEDURE public.mark_indexing_recovery_primary()
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_started TIMESTAMPTZ;
    v_timeline TEXT;
BEGIN
    IF pg_is_in_recovery() OR current_setting('transaction_read_only')::boolean
        OR current_setting('default_transaction_read_only')::boolean THEN
        RETURN;
    END IF;

    v_started := pg_postmaster_start_time();
    v_timeline := left(pg_walfile_name(pg_current_wal_insert_lsn()), 8);
    INSERT INTO public.indexing_recovery_primary AS p
        (singleton, postmaster_started_at, wal_timeline, writable_since)
    VALUES (TRUE, v_started, v_timeline, clock_timestamp())
    ON CONFLICT (singleton) DO UPDATE
    SET postmaster_started_at = EXCLUDED.postmaster_started_at,
        wal_timeline = EXCLUDED.wal_timeline,
        writable_since = clock_timestamp()
    WHERE p.postmaster_started_at IS DISTINCT FROM EXCLUDED.postmaster_started_at
        OR p.wal_timeline IS DISTINCT FROM EXCLUDED.wal_timeline;
END;
$$;

CREATE PROCEDURE public.reclaim_expired_indexing_jobs(IN p_batch_size INTEGER DEFAULT 100)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '100ms'
AS $$
DECLARE
    v_primary public.indexing_recovery_primary%ROWTYPE;
    v_candidate RECORD;
    v_job public.indexing_job%ROWTYPE;
    v_document_epoch BIGINT;
    v_now TIMESTAMPTZ;
BEGIN
    -- standby/읽기 전용에서는 잠금·기준 시각 변경도 하지 않는다.
    IF pg_is_in_recovery() OR current_setting('transaction_read_only')::boolean
        OR current_setting('default_transaction_read_only')::boolean THEN
        RETURN;
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Recovery requires READ COMMITTED isolation';
    END IF;
    IF p_batch_size IS NULL OR p_batch_size < 1 OR p_batch_size > 1000 THEN
        RAISE EXCEPTION 'Recovery batch size must be between 1 and 1000';
    END IF;

    SELECT * INTO v_primary FROM public.indexing_recovery_primary
    WHERE singleton FOR SHARE SKIP LOCKED;
    IF NOT FOUND THEN RETURN; END IF;
    IF v_primary.postmaster_started_at <> pg_postmaster_start_time()
        OR v_primary.wal_timeline <> left(pg_walfile_name(pg_current_wal_insert_lsn()), 8)
        OR clock_timestamp() < v_primary.writable_since + INTERVAL '30 seconds' THEN
        -- 미기록 기동/승격에서는 이전 Primary 의 기준 시각으로 회수를 허용하지 않는다.
        RETURN;
    END IF;

    -- 후보 수 자체를 제한한다. 잠긴 후보를 건너뛰면 이번 배치의 회수 수는 더 작을 수 있다.
    FOR v_candidate IN
        SELECT j.id, j.document_id, j.worker_id, j.execution_epoch,
            j.lease_until, d.execution_epoch AS document_epoch
        FROM public.indexing_job j JOIN public.document d ON d.id = j.document_id
        WHERE j.status = 'PROCESSING' AND j.lease_until IS NOT NULL
            AND j.lease_until <= clock_timestamp()
        ORDER BY j.lease_until, j.enqueue_seq
        LIMIT p_batch_size
    LOOP
        -- Worker 와 같은 문서 → Job 순서. heartbeat/결과 쓰기를 기다리지 않는다.
        SELECT execution_epoch INTO v_document_epoch FROM public.document
        WHERE id = v_candidate.document_id FOR UPDATE SKIP LOCKED;
        IF NOT FOUND THEN CONTINUE; END IF;
        SELECT * INTO v_job FROM public.indexing_job
        WHERE id = v_candidate.id FOR UPDATE SKIP LOCKED;
        IF NOT FOUND THEN CONTINUE; END IF;

        -- 두 잠금 획득 후의 DB 시각. 후보 조회 이후 갱신된 소유권/lease 는 회수하지 않는다.
        v_now := clock_timestamp();
        IF v_job.status <> 'PROCESSING'
            OR v_job.document_id <> v_candidate.document_id
            OR v_job.worker_id IS DISTINCT FROM v_candidate.worker_id
            OR v_job.execution_epoch <> v_candidate.execution_epoch
            OR v_document_epoch <> v_candidate.document_epoch
            OR v_job.lease_until IS DISTINCT FROM v_candidate.lease_until
            OR v_job.lease_until IS NULL OR v_job.lease_until > v_now THEN
            CONTINUE;
        END IF;

        UPDATE public.indexing_job
        SET status = 'PENDING', worker_id = NULL, lease_until = NULL,
            next_retry_at = NULL, updated_at = v_now
        WHERE id = v_job.id;
        -- phase/오류/started_at/completed_at/outcome 은 보존한다. CHECK 제약이 추가 초기화를
        -- 요구하지 않으며, Worker claim 은 outcome 을 비우고 실행 경로가 phase 를 다시 기록한다.
    END LOOP;
END;
$$;

REVOKE ALL ON PROCEDURE public.mark_indexing_recovery_primary() FROM PUBLIC;
REVOKE ALL ON PROCEDURE public.reclaim_expired_indexing_jobs(INTEGER) FROM PUBLIC;

-- 설치/계정 생성은 하지 않는다. 현재 등록 계정은 프로시저 소유자로서 실행할 수 있다.
-- 별도 기존 실행 계정을 쓰는 경우 실제 계정 정보에 맞춰 EXECUTE 권한을 추가한다.

-- 초기 설치도 기록 시점부터 유예한다. 이후 재기동/승격은 Patroni 콜백이 새 마커를 기록한다.
CALL public.mark_indexing_recovery_primary();
