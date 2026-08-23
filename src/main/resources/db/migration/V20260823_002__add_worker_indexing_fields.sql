-- =============================================================================
-- Worker indexing fields
--
-- Worker에서 사용하는 인덱싱 버전 및 Kafka record 메타데이터를
-- core DB schema에 통합한다.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- document_version
-- -----------------------------------------------------------------------------

ALTER TABLE document_version
    ADD COLUMN embedding_version_no BIGINT NOT NULL;

ALTER TABLE document_version
    ADD CONSTRAINT ck_document_version_embedding_version_no
        CHECK (embedding_version_no > 0);

COMMENT ON COLUMN document_version.embedding_version_no IS
    '검색 버전 승격 순서를 결정하는 단조 증가 버전 번호.';


-- -----------------------------------------------------------------------------
-- indexing_job
-- -----------------------------------------------------------------------------

ALTER TABLE indexing_job
    ADD COLUMN phase VARCHAR(30),
    ADD COLUMN kafka_topic VARCHAR(255) NOT NULL,
    ADD COLUMN kafka_partition INTEGER NOT NULL,
    ADD COLUMN kafka_offset BIGINT NOT NULL;

ALTER TABLE indexing_job
    ADD CONSTRAINT ck_indexing_job_kafka_topic
        CHECK (BTRIM(kafka_topic) <> ''),
    ADD CONSTRAINT ck_indexing_job_kafka_partition
        CHECK (kafka_partition >= 0),
    ADD CONSTRAINT ck_indexing_job_kafka_offset
        CHECK (kafka_offset >= 0);

COMMENT ON COLUMN indexing_job.phase IS
    '현재 인덱싱 처리 단계.';

COMMENT ON COLUMN indexing_job.kafka_topic IS
    '수신한 Kafka record의 topic.';

COMMENT ON COLUMN indexing_job.kafka_partition IS
    '수신한 Kafka record의 partition.';

COMMENT ON COLUMN indexing_job.kafka_offset IS
    '수신한 Kafka record의 offset.';
