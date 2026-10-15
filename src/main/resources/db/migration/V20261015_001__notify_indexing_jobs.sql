-- Worker 계약: LISTEN indexing_job 을 커밋한 뒤 대기 Job 을 조회한다.
-- 알림은 커밋 후 전달되는 깨우기 신호다. 연결 단절과 RETRY_WAIT 의 재시도 시각은 폴링으로 보완한다.
-- 빈 payload 로 같은 트랜잭션의 여러 Job 알림을 하나로 합친다. 선점 순서는 DB 에서 결정한다.
CREATE FUNCTION public.fn_notify_indexing_job()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_catalog.pg_notify('indexing_job', '');
    RETURN NEW;
END;
$$;

-- 버전 저장·문서 삭제 트리거와 API 의 수동 재인덱싱 INSERT 를 모두 포함한다.
CREATE TRIGGER trg_indexing_job_notify_insert
    AFTER INSERT ON public.indexing_job
    FOR EACH ROW
    WHEN (NEW.status = 'PENDING')
    EXECUTE FUNCTION public.fn_notify_indexing_job();

-- 만료 Job 회수는 새 행을 만들지 않고 기존 행을 PENDING 으로 되돌린다.
CREATE TRIGGER trg_indexing_job_notify_pending
    AFTER UPDATE OF status ON public.indexing_job
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status AND NEW.status = 'PENDING')
    EXECUTE FUNCTION public.fn_notify_indexing_job();
