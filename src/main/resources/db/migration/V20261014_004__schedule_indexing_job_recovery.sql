-- 설치된 pg_cron 에 작업만 등록한다. 확장 설치/계정 생성/노드 설정은 하지 않는다.
-- 확인된 환경: pg_cron 1.6, cron.database_name=aidocs, cron.use_background_workers=off.
-- 현재 작성본은 Flyway 계정으로 등록한다. 실제 실행 계정 정보에 맞춰 확정한다.
SELECT cron.schedule(
    'aidocs-reclaim-expired-indexing-jobs',
    '30 seconds',
    'SET statement_timeout = ''5s''; CALL public.reclaim_expired_indexing_jobs(100);'
);
