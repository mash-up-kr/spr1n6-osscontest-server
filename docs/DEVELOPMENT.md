**한국어** | [English](DEVELOPMENT.en.md)

# 개발 가이드

기여자를 위한 문서다. 앱을 띄워 보기만 할 때는 [README 의 시작하기](../README.md#시작하기)로 충분하다.

---

## 개발 방식 고르기

| 방식 | 앱 실행 | DB | 쓰는 때 |
|---|---|---|---|
| `local` 프로파일 | gradle | 컨테이너 PostgreSQL | 서버 코드를 고치며 개발할 때 |
| `dev` 프로파일 | gradle | 공유 Tmax OpenSQL (SSH 터널) | 실제 DB 동작을 확인할 때 |
| 전체 스택 | 컨테이너 | 컨테이너 PostgreSQL | 릴레이·워커까지 이어지는지 볼 때 |

서버 코드만 만질 때는 `local`이 가장 빠르다. 앱을 컨테이너로 다시 빌드하지 않아도 된다.

---

## local 프로파일

Docker로 PostgreSQL 17.8과 MinIO를 띄우고 애플리케이션이 여기에 접속한다. 이미지 태그를 `0.8.1-pg17`로 고정해 운영(Tmax OpenSQL v3.0, PostgreSQL 17.8 기반 + pgvector 0.8.1)과 같은 버전을 쓴다.

```bash
docker compose -f docker-compose.local.yml up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

원본 파일은 MinIO에 저장한다. 컨테이너가 뜰 때 `aidocs-documents` 버킷이 함께 만들어지므로 별도 준비가 필요 없고, `http://localhost:9001` 콘솔에서 확인할 수 있다.

DB와 MinIO에 직접 붙어볼 때 쓰는 계정과 포트는 `docker-compose.local.yml`에 있다.

Flyway는 기동할 때 자동으로 실행된다.

---

## dev 프로파일

공유 Tmax OpenSQL에 접속한다. DB 포트는 외부에 열려 있지 않아 SSH 터널을 거친다. 접속 정보는 `.env`로 관리한다.

`tunnel.sh start`는 DB를 띄우는 것이 아니라 내 컴퓨터의 `15432` 포트를 리모트 DB까지 이어주는 통로를 여는 명령이다. 앱은 이 포트를 로컬 DB처럼 보고 접속하므로, 앱을 실행하기 전에 터널이 먼저 열려 있어야 한다.

```bash
cp .env.dev.example .env
# .env를 열어 값을 채웁니다. 값과 pem 파일은 팀 내부에서 공유받으세요.

./scripts/tunnel.sh start   # 터널을 열어둔다. 백그라운드로 유지된다
./gradlew bootRun --args='--spring.profiles.active=dev'
```

MinIO는 별도 파일로 띄운다.

```bash
docker compose -f docker-compose.dev.yml up -d
```

### 터널 다루기

터널은 백그라운드에서 계속 떠 있어서 터미널을 닫아도 유지된다. 한 번 열어두면 앱을 껐다 켜도 다시 열 필요가 없고, 컴퓨터를 재부팅하면 사라지므로 다시 `start` 한다. 이미 열려 있을 때 `start`를 또 해도 중복으로 열리지 않는다.

```bash
./scripts/tunnel.sh status
./scripts/tunnel.sh stop
```

앱이 `Connection refused`로 뜬다면 대개 터널이 닫힌 경우이니 `status`부터 확인한다.

pem 파일은 프로젝트 폴더 밖에 두고 `chmod 600`으로 권한을 맞춘다. `.env`에는 키 값이 아니라 파일 경로를 적고, 실행 위치와 무관하도록 절대경로나 `~/`로 시작하는 경로를 쓴다.

로컬 포트 기본값은 `15432`이다. `local` 프로파일의 Docker PostgreSQL이 5432를 쓰기 때문에, 터널까지 5432로 열면 컨테이너가 떠 있을 때 터널이 열리지 않거나 의도한 것과 다른 DB에 붙게 된다.

### 커넥션 아껴 쓰기

공유 DB는 `max_connections`가 100이다. 앱 하나가 HikariCP 기본값대로 10개를 상시 점유하므로, 팀원 여럿이 동시에 띄우면 금방 찬다. 쓰지 않을 때는 앱을 내려 주세요.

누가 붙어 있는지는 이렇게 본다.

```sql
SELECT usename, application_name, client_addr, state, count(*)
  FROM pg_stat_activity
 GROUP BY 1,2,3,4 ORDER BY count DESC;
```

터널을 거친 접속은 `client_addr`이 모두 `127.0.0.1`로 보여 서로 구분되지 않는다.

### 마이그레이션 적용

`dev` 프로파일은 Flyway 자동 실행이 꺼져 있다. 앱을 띄워도 공유 DB의 스키마는 바뀌지 않는다.

적용이 필요할 때만 켜서 실행한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=dev --spring.flyway.enabled=true'
```

적용할 때는 반드시 팀에 공유한다. 기본적으로 [인프라 담당자](https://github.com/mingdodev)가 수행하고, 다른 팀원이 수행해야 한다면 미리 팀에 알린 뒤 진행한다. **한 번 적용된 마이그레이션 파일을 나중에 수정하면 나머지 팀원 전원이 체크섬 불일치로 앱을 띄울 수 없게 된다.**

---

## 전체 스택

릴레이·워커까지 이어지는지 확인할 때 쓴다. 실행 방법은 [README 의 시작하기](../README.md#시작하기)와 같다.

업로드 → 릴레이 → 워커 → 검색이 한 번이라도 끝까지 통과하는 것을 본 뒤에 서버에 올린다.

공유 DB에 붙여야 한다면 터널 오버레이를 얹는다. 이때는 마이그레이션이 팀 전체에 영향을 주므로 위의 적용 규칙을 따른다.

```bash
cp .env.tunnel.example .env.tunnel
docker compose -f docker-compose.yml -f docker-compose.tunnel.yml \
  --env-file .env.tunnel up -d
```

컨테이너 PostgreSQL로는 확인할 수 없는 것이 있다. ARIA-256 암호화와 Patroni HA 구성, Tmax OpenSQL 고유 동작이다. ARIA는 Tmax OpenCrypto에만 있어 로컬 `pgcrypto`로는 돌지 않으므로, base는 같은 코드가 AES-256으로 돌게 한다.

---

## 컴포즈 파일

| 파일 | 용도 |
|---|---|
| `docker-compose.yml` | 전체 스택. DB를 컨테이너로 함께 띄운다 |
| `docker-compose.tunnel.yml` | 오버레이. `db`를 공유 DB로 가는 SSH 터널로 교체한다 |
| `docker-compose.deploy.yml` | 오버레이. 호스트 DB로 TCP 중계하고 Caddy로 HTTPS를 종료한다 |
| `docker-compose.local.yml` | `local` 프로파일용 인프라 (PostgreSQL, MinIO) |
| `docker-compose.dev.yml` | `dev` 프로파일용 인프라 (MinIO) |

기본형을 자기완결적인 쪽에 두고 특수한 경우를 오버레이로 얹는다.

---

## 프로파일 구성

`local`과 `dev`는 DB 위치, 마이그레이션 실행 방식, 암호화 알고리즘이 다르다. 애플리케이션 코드는 동일하다.

| 항목 | `local` | `dev` |
|---|---|---|
| DB | Docker PostgreSQL 17.8 + pgvector 0.8.1 | 공유 Tmax OpenSQL v3.0 + pgvector 0.8.1 |
| 접속 경로 | 직접 | SSH 터널 |
| 접속 정보 | `application-local.yml` | `.env` |
| 암호화 알고리즘 | AES-256 | ARIA-256 |
| CORS 허용 | `application-local.yml` | `.env` |
| SQL 로깅 | ON | OFF |
| Flyway | 기동 시 자동 실행 | 수동 실행 |

---

## 암호화

`app_user.name`과 `document_version.original_filename`은 암호화해 저장한다. 알고리즘은 `app.encryption_cipher` 세션 설정으로 정하고, 지정하지 않으면 ARIA-256을 쓴다.

ARIA는 Tmax OpenCrypto 1.0에만 있으므로 `dev`는 ARIA-256을, 로컬 PostgreSQL의 `pgcrypto`에는 없으므로 `local`은 AES-256을 쓴다. 로컬 컨테이너의 `pgcrypto`는 `scripts/local-db/00-extensions.sql`이 설치한다. dev DB에는 `opencrypto` 확장이 사전에 설치되어 있어야 한다.

암호문에 알고리즘 정보가 들어 있어 `app_decrypt`는 어느 쪽으로 저장된 값이든 복호화한다. ARIA 전환 전에 저장된 AES-256 암호문도 그대로 읽힌다.

`DB_ENCRYPTION_KEY`는 애플리케이션 시작 시 HikariCP가 생성하는 각 DB Connection의 `app.encryption_key` 세션 설정으로 전달된다. 키가 바뀌면 기존 암호문을 복호화할 수 없으므로 운영 중에는 동일한 값을 안전하게 보관하고 모든 애플리케이션 인스턴스에 동일하게 설정해야 한다.

---

## dev 프로파일 환경 변수

`.env.dev.example`를 복사해 사용한다.

| 변수명 | 필수 | 설명 |
|---|:--:|---|
| `DB_HOST` | O | DB 호스트. 터널을 거치므로 `127.0.0.1` |
| `DB_PORT` | X | 포트 (기본 5432). `TUNNEL_LOCAL_PORT`와 같은 값 |
| `DB_NAME` | O | 데이터베이스명 |
| `DB_USERNAME` | O | 접속 계정 |
| `DB_PASSWORD` | O | 접속 비밀번호 |
| `DB_ENCRYPTION_KEY` | O | DB 연결의 `app.encryption_key`에 주입할 고엔트로피 Base64 키 |
| `DB_ENCRYPTION_CIPHER` | X | 암호화 알고리즘 (기본 `aria256`). ARIA를 못 쓰는 환경에서만 `aes256`으로 바꾼다 |
| `SSH_HOST` | O | 터널을 붙일 서버 주소 |
| `SSH_PORT` | X | SSH 포트 |
| `SSH_USER` | O | SSH 계정 |
| `SSH_KEY_PATH` | O | pem 파일 경로 (절대경로 또는 `~/`) |
| `TUNNEL_LOCAL_PORT` | X | 로컬에서 열 포트 (기본 15432) |
| `TUNNEL_REMOTE_HOST` | X | 서버에서 본 DB 주소 (기본 127.0.0.1) |
| `TUNNEL_REMOTE_PORT` | X | 서버에서 본 DB 포트 (기본 5432) |
| `STORAGE_ENDPOINT` | O | 오브젝트 스토리지 주소 |
| `STORAGE_REGION` | O | 리전. MinIO는 무시하지만 SDK가 서명에 쓰므로 값이 있어야 한다 |
| `STORAGE_ACCESS_KEY` | O | 액세스 키 |
| `STORAGE_SECRET_KEY` | O | 시크릿 키 |
| `STORAGE_BUCKET` | O | 원본 파일을 담을 버킷 |
| `OPENAI_API_KEY` | O | 검색 질의를 `text-embedding-3-small` 1536차원 벡터로 변환할 OpenAI API 키 |
| `CORS_ALLOWED_ORIGINS` | O | CORS를 허용할 origin. 쉼표로 여러 개를 넘긴다. 값이 없으면 앱이 뜨지 않는다 |

`.env`와 실제 접속 정보, pem 파일은 커밋하지 않는다.

---

## 협업 규칙

### 브랜치

| 브랜치 | 용도 |
|---|---|
| `main` | 통합 브랜치 |
| `feat/*` | 기능 개발 |
| `fix/*` | 버그 수정 |
| `refactor/*` | 리팩터링 |
| `docs/*` | 문서 작업 |
| `chore/*` | 설정, 빌드, 의존성 |

작업 브랜치는 `main`에서 분기하고, 완료 후 PR로 `main`에 머지한다.

### 커밋 메시지

제목은 `<type>: <subject>` 형식을 따른다. 바디는 자유롭게 작성한다.

```
feat: 문서 업로드 API 구현
fix: 대용량 PDF 파싱 시 OOM 수정
docs: README 프로파일 구성 추가
refactor: DocumentService 책임 분리
test: 문서 검색 통합 테스트 추가
chore: Spring Boot 4.1.0 업그레이드
```

---

## 관련 문서

- [기여 안내](../CONTRIBUTING.md) — 브랜치, 커밋 메시지, PR
- [코드 컨벤션](CODE_CONVENTIONS.md)
- [API 명세](API_SPEC.md)
