-- =============================================================================
-- 암호화 알고리즘을 세션 설정으로 받는다
--
-- app.encryption_cipher 를 지정하지 않으면 ARIA-256 을 쓴다. 운영과 dev 는 지정하지 않는다.
-- ARIA 는 Tmax OpenCrypto 확장에만 있어 로컬 pgcrypto 에서는 이 함수가 실패한다.
-- 로컬은 aes256 을 넣어 같은 코드로 돌린다.
--
-- app_decrypt 는 그대로 둔다. PGP 메시지가 자기 알고리즘을 헤더에 담고 있어
-- ARIA-256 과 AES-256 을 모두 푼다.
-- =============================================================================

CREATE OR REPLACE FUNCTION app_encrypt(p_plain bytea)
RETURNS bytea AS $$
    SELECT pgp_sym_encrypt_bytea(
        p_plain,
        current_setting('app.encryption_key'),
        'cipher-algo=' || coalesce(current_setting('app.encryption_cipher', true), 'aria256')
    );
$$ LANGUAGE sql VOLATILE STRICT;

COMMENT ON FUNCTION app_encrypt(bytea) IS
    '세션의 app.encryption_key 로 평문 UTF-8 바이트열을 암호화한다. 알고리즘은 app.encryption_cipher, 기본값은 ARIA-256.';
