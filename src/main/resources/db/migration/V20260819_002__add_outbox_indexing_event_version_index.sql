CREATE INDEX idx_outbox_indexing_event_version
    ON outbox_event (document_version_id, created_at DESC)
    WHERE event_type = 'INDEXING_REQUESTED';
