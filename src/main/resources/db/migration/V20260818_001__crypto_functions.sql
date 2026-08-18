-- =============================================================================
-- 암호화 컬럼 접근 함수
--
-- 대상: app_user.name, document_version.original_filename
-- 엔티티가 @ColumnTransformer 로 이 두 함수를 호출한다.
--
-- 지금은 값을 그대로 통과시킨다. 암호화 본체는 후속 작업에서
-- CREATE OR REPLACE 로 본문만 교체한다. 시그니처는 바뀌지 않는다.
-- =============================================================================

CREATE OR REPLACE FUNCTION app_encrypt(p_plain bytea)
RETURNS bytea AS $$
    SELECT p_plain;
$$ LANGUAGE sql IMMUTABLE STRICT;

CREATE OR REPLACE FUNCTION app_decrypt(p_cipher bytea)
RETURNS bytea AS $$
    SELECT p_cipher;
$$ LANGUAGE sql IMMUTABLE STRICT;

COMMENT ON FUNCTION app_encrypt(bytea) IS
    '평문 UTF-8 바이트열을 암호화 컬럼에 넣을 형태로 바꾼다. 아직 암호화하지 않는다.';
COMMENT ON FUNCTION app_decrypt(bytea) IS
    '암호화 컬럼 값을 평문 UTF-8 바이트열로 되돌린다. 아직 복호화하지 않는다.';
