-- 로컬 컨테이너 초기화 스크립트.
--
-- Job 회수 스케줄 마이그레이션(V20261014_004)은 pg_cron 이 설치돼 있다는 전제로 작업만 등록한다.
-- 패키지와 shared_preload_libraries 는 docker-compose.local.yml 의 postgres 서비스가 맞춘다.
-- CI 는 00-extensions.sql 만 실행하고 pg_cron 은 워크플로에서 따로 설치하므로 파일을 나눈다.
CREATE EXTENSION IF NOT EXISTS pg_cron;
