-- 로컬 컨테이너 초기화 스크립트.
--
-- dev 는 암호 확장(Tmax OpenCrypto)이 Flyway 밖에서 설치돼 있다는 전제로 동작한다.
-- 로컬도 같은 전제를 맞춰 준다. ARIA 는 없으므로 local 프로필이 aes256 을 쓴다.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
