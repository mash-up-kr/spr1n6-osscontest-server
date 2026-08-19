-- =============================================================================
-- 기존 문서에 기본 권한 백필
--
-- 문서 접근과 검색이 document_access_scope 만 참조하므로,
-- 이 마이그레이션 이전에 만들어진 문서에도 소유자 ADMIN 과 테넌트 READ 를 넣는다.
-- =============================================================================

INSERT INTO document_access_scope (tenant_id, document_id, principal_type, principal_id, permission, granted_by_principal_id)
SELECT d.tenant_id, d.id, 'USER', d.owner_principal_id, 'ADMIN', d.owner_principal_id
FROM document d
WHERE d.owner_principal_id ~ '^[0-9]+$'
ON CONFLICT (document_id, principal_type, principal_id) DO NOTHING;

INSERT INTO document_access_scope (tenant_id, document_id, principal_type, principal_id, permission, granted_by_principal_id)
SELECT d.tenant_id, d.id, 'TENANT', d.tenant_id::text, 'READ', d.owner_principal_id
FROM document d
ON CONFLICT (document_id, principal_type, principal_id) DO NOTHING;
