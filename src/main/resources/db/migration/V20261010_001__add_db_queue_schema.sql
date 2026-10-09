-- =============================================================================
-- DB queue schema
--
-- Kafka 기반 작업 전달을 DB Job 큐로 대체하기 위해 document, indexing_job에
-- 실행 번호와 큐 컬럼을 추가한다.
--
-- 새 컬럼은 모두 DEFAULT 또는 NULL 허용이라, 새 컬럼을 모르는 기존 Worker 코드가
-- INSERT해도 동작한다. kafka_topic/partition/offset 컬럼과
-- uq_indexing_job_active_version은 변경하지 않는다.
--
-- Flyway가 이 파일을 하나의 트랜잭션으로 실행한다. ALTER TABLE이 테이블을 잠그므로
-- 실행 중 들어오는 INSERT는 완료 후 반영되어 enqueue_seq 누락이 생기지 않는다.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- document
-- -----------------------------------------------------------------------------

ALTER TABLE document
    ADD COLUMN execution_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE document
    ADD CONSTRAINT ck_document_execution_epoch
        CHECK (execution_epoch >= 0);

COMMENT ON COLUMN document.execution_epoch IS
    '문서 단위 실행 번호. 해당 문서의 Job을 claim할 때마다 증가하며 초기화하지 않는다. 이전 Worker의 늦은 변경을 차단하는 데 사용.';


-- -----------------------------------------------------------------------------
-- indexing_job
-- -----------------------------------------------------------------------------

CREATE SEQUENCE indexing_job_enqueue_seq;

ALTER TABLE indexing_job
    ADD COLUMN job_type VARCHAR(20) NOT NULL DEFAULT 'INDEX',
    ADD COLUMN enqueue_seq BIGINT,
    ADD COLUMN execution_epoch BIGINT,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN outcome VARCHAR(40);

-- 기존 행은 id 순서대로 enqueue_seq를 부여한다.
WITH ordered AS MATERIALIZED (
    SELECT id
    FROM indexing_job
    ORDER BY id
),
numbered AS (
    SELECT id, nextval('indexing_job_enqueue_seq') AS seq
    FROM ordered
)
UPDATE indexing_job j
SET enqueue_seq = n.seq
FROM numbered n
WHERE j.id = n.id;

ALTER TABLE indexing_job
    ALTER COLUMN enqueue_seq SET DEFAULT nextval('indexing_job_enqueue_seq'),
    ALTER COLUMN enqueue_seq SET NOT NULL;

ALTER SEQUENCE indexing_job_enqueue_seq OWNED BY indexing_job.enqueue_seq;

ALTER TABLE indexing_job
    ADD CONSTRAINT ck_indexing_job_job_type
        CHECK (job_type IN ('INDEX', 'DELETE')),
    ADD CONSTRAINT ck_indexing_job_execution_epoch
        CHECK (execution_epoch IS NULL OR execution_epoch > 0);

CREATE UNIQUE INDEX uq_indexing_job_enqueue_seq
    ON indexing_job (enqueue_seq);

COMMENT ON COLUMN indexing_job.job_type IS
    'INDEX: 임베딩 / DELETE: 기존 청크·벡터 정리. 삭제된 문서에서도 DELETE 작업 자체는 수행한다.';

COMMENT ON COLUMN indexing_job.enqueue_seq IS
    '전역 증가형 등록 순서. 실행 가능한 Job을 오래된 순으로 고르고, 같은 문서의 선행 Job을 비교하는 데 사용. 값 사이에 빈 번호가 생길 수 있다.';

COMMENT ON COLUMN indexing_job.execution_epoch IS
    'claim 시 document.execution_epoch로부터 받은 실행 번호. 최초 claim 전에는 NULL.';

COMMENT ON COLUMN indexing_job.lease_until IS
    '현재 실행권 만료 시각. heartbeat로 연장하며, 실행권이 없으면 NULL.';

COMMENT ON COLUMN indexing_job.outcome IS
    '완료 사유. 예: INDEXED, DELETED, SKIPPED_DOCUMENT_DELETED. 값이 늘 수 있어 CHECK 제약은 두지 않는다.';


-- -----------------------------------------------------------------------------
-- indexing_job 미종결 Job 조회용 partial index
--
-- 완료·실패 이력 전체를 매번 순회하지 않도록 미종결 상태만 인덱싱한다.
-- -----------------------------------------------------------------------------

-- 같은 문서의 앞선 미종결 Job 확인
CREATE INDEX ix_indexing_job_open_document_seq
    ON indexing_job (document_id, enqueue_seq)
    WHERE status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT');

-- claim 후보를 등록 순서대로 조회
CREATE INDEX ix_indexing_job_claimable_seq
    ON indexing_job (enqueue_seq)
    WHERE status IN ('PENDING', 'RETRY_WAIT');

-- 재시도 시각이 된 Job 조회
CREATE INDEX ix_indexing_job_retry_due
    ON indexing_job (next_retry_at)
    WHERE status = 'RETRY_WAIT';

-- lease가 만료된 Job 회수
CREATE INDEX ix_indexing_job_lease_expiry
    ON indexing_job (lease_until)
    WHERE status = 'PROCESSING';
