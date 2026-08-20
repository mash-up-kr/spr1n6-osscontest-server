-- =============================================================================
-- 암호화 컬럼 접근 함수 구현
--
-- 암호화 키는 애플리케이션이 각 DB 세션의 app.encryption_key 설정에 주입한다.
-- ARIA를 지원하는 Tmax OpenCrypto 확장이 사전에 설치되어 있어야 한다.
-- 대상: app_user.name, document_version.original_filename
-- =============================================================================

CREATE OR REPLACE FUNCTION app_encrypt(p_plain bytea)
RETURNS bytea AS $$
    SELECT pgp_sym_encrypt_bytea(
        p_plain,
        current_setting('app.encryption_key'),
        'cipher-algo=aria256'
    );
$$ LANGUAGE sql VOLATILE STRICT;

CREATE OR REPLACE FUNCTION app_decrypt(p_cipher bytea)
RETURNS bytea AS $$
    SELECT pgp_sym_decrypt_bytea(
        p_cipher,
        current_setting('app.encryption_key')
    );
$$ LANGUAGE sql STABLE STRICT;

COMMENT ON FUNCTION app_encrypt(bytea) IS
    '세션의 app.encryption_key를 사용해 평문 UTF-8 바이트열을 ARIA-256으로 암호화한다.';
COMMENT ON FUNCTION app_decrypt(bytea) IS
    '세션의 app.encryption_key를 사용해 ARIA-256 또는 기존 AES-256 암호문을 평문 UTF-8 바이트열로 복호화한다.';
