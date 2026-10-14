**한국어** | [English](DEVELOPMENT.en.md)

# 개발 가이드

기여자를 위한 문서다. 앱을 띄워 보기만 할 때는 [README 의 시작하기](../README.md#시작하기)로 충분하다.

---

## 실행 환경

| 프로파일 | 용도 | DB | 컴포즈 파일 |
|---|---|---|---|
| `local` | 개발·테스트 | 컨테이너 PostgreSQL | `docker-compose.local.yml` |
| `dev` | DB 클러스터에 붙여 보는 시험 (HA 시험, 기능 시연) | DB 클러스터 (SSH 터널 → OpenProxy) | `docker-compose.dev.yml` |

각 프로파일은 앱을 gradle로 띄우거나, 컴포즈로 server·worker까지 함께 띄운다. 서버 코드만 만질 때는 인프라만 컨테이너로 띄우고 gradle로 앱을 실행하는 쪽이 가장 빠르다. 테스트는 `local` 인프라로 돈다.

---

## local 프로파일

Docker로 PostgreSQL 17.8과 MinIO를 띄우고 애플리케이션이 여기에 접속한다. 이미지 태그를 `0.8.1-pg17`로 고정해 DB 클러스터(Tmax OpenSQL v3.0, PostgreSQL 17.8 기반 + pgvector 0.8.1)와 같은 버전을 쓴다. 테스트도 이 DB와 MinIO로 돈다.

```bash
docker compose -f docker-compose.local.yml up -d postgres minio minio-init
./gradlew bootRun --args='--spring.profiles.active=local'
```

server·worker까지 컨테이너로 띄우려면 서비스 이름 없이 올린다. 워커는 기본 2개가 실행된다.

```bash
docker compose -f docker-compose.local.yml up -d --build
```

`.env` 파일은 필요 없다. DB·암호화 키·MinIO 값은 `application-local.yml`과 `docker-compose.local.yml`에 로컬 전용으로 고정돼 있다. 외부 API 키만 넘긴다.

| 변수 | 필수 | 설명 |
|---|:--:|---|
| `OPENAI_API_KEY` | X | 임베딩. 없으면 서버는 뜨지만 인덱싱과 벡터 검색이 동작하지 않는다 |
| `COHERE_API_KEY` | X | 검색 결과 재정렬. 없으면 RRF 순서로 응답한다 |

- gradle로 띄울 때는 셸 환경 변수로만 읽는다(`export OPENAI_API_KEY=...`). `local` 프로파일은 `.env`를 읽지 않는다.
- 컴포즈로 띄울 때는 셸 환경 변수나 프로젝트 루트의 `.env`에서 읽는다. dev용 `.env`가 있어도 이 두 값만 가져간다.

원본 파일은 MinIO에 저장한다. 컨테이너가 뜰 때 `aidocs-documents` 버킷이 함께 만들어지므로 별도 준비가 필요 없고, `http://localhost:9001` 콘솔에서 확인할 수 있다.

DB와 MinIO에 직접 붙어볼 때 쓰는 계정과 포트는 `docker-compose.local.yml`에 있다.

Flyway는 기동할 때 자동으로 실행된다.

---

## dev 프로파일

DB 클러스터(Tmax OpenSQL + Patroni)에 OpenProxy를 거쳐 접속한다. 접속 주소는 OpenProxy VIP `10.40.1.100:6432`이고, VPC 밖에 열려 있지 않아 `db-01`을 거치는 SSH 터널로 붙는다. 접속 정보는 `.env`로 관리한다.

`tunnel.sh start`는 DB를 띄우는 것이 아니라 내 컴퓨터의 `15432` 포트를 VIP까지 이어주는 통로를 여는 명령이다. 앱은 이 포트를 로컬 DB처럼 보고 접속하므로, 앱을 실행하기 전에 터널이 먼저 열려 있어야 한다.

```bash
cp .env.dev.example .env
# .env를 열어 비밀번호·키·pem 경로를 채웁니다. 값과 pem 파일은 팀 내부에서 공유받으세요.

docker compose -f docker-compose.dev.yml up -d minio minio-init   # 워커와 같은 MinIO (호스트 포트 9010)
./scripts/tunnel.sh start               # 터널을 열어둔다. 백그라운드로 유지된다
./gradlew bootRun --args='--spring.profiles.active=dev'
```

`dev` 프로파일은 연결마다 `SET SERVER ROLE TO 'default'`를 보낸다. 일반 PostgreSQL에는 붙지 않는다.

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

`aidocs_session` 풀은 OpenProxy 한 대당 DB 연결 14개가 상한이다. 서버 하나가 10개를 상시 점유하므로 클러스터에 붙는 서버는 팀 전체에서 사실상 하나다. gradle로 띄운 `dev` 앱과 dev 스택의 server도 함께 띄우지 않는다. 상한을 넘은 연결은 10초 기다린 뒤 `AllServersDown`(`58000`)으로 실패한다. 쓰지 않을 때는 앱을 내려 주세요.

검색은 `aidocs_ro` 풀(Replica)을 따로 쓴다. 상한은 8개이고 서버 하나가 최대 8개를 연다.

누가 붙어 있는지는 이렇게 본다.

```sql
SELECT usename, application_name, client_addr, state, count(*)
  FROM pg_stat_activity
 GROUP BY 1,2,3,4 ORDER BY count DESC;
```

OpenProxy를 거친 접속은 `client_addr`이 OpenProxy 서버 주소로 보여 앱별로 구분되지 않는다.

### 마이그레이션 적용

`dev` 프로파일은 Flyway 자동 실행이 꺼져 있다. 앱을 띄워도 클러스터의 스키마는 바뀌지 않는다.

적용이 필요할 때만 켜서 실행한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=dev --spring.flyway.enabled=true'
```

60초 넘게 걸리는 마이그레이션은 `DB_SOCKET_TIMEOUT=0`을 함께 준다.

만료 indexing Job 회수 마이그레이션은 기존 pg_cron에 프로시저 호출을 등록한다.

적용할 때는 반드시 팀에 공유한다. 기본적으로 [인프라 담당자](https://github.com/mingdodev)가 수행하고, 다른 팀원이 수행해야 한다면 미리 팀에 알린 뒤 진행한다. **한 번 적용된 마이그레이션 파일을 나중에 수정하면 나머지 팀원 전원이 체크섬 불일치로 앱을 띄울 수 없게 된다.**

### dev 스택

server·worker·MinIO를 컨테이너로 띄우고, DB는 `db-tunnel` 컨테이너가 연 SSH 터널로 클러스터에 붙는다. 호스트에서 `tunnel.sh`를 켤 필요가 없다. 워커 2개는 서버가 마이그레이션을 마치고 정상 상태가 된 뒤 시작한다.

```bash
cp .env.dev.example .env   # 값을 채운다
docker compose -f docker-compose.dev.yml up -d --build
```

| 컨테이너 | 접속 |
|---|---|
| `server` | `db-tunnel:6432` / `aidocs_session` 풀, `dev` 프로파일 |
| `worker` | `db-tunnel:6432` / `aidocs` 풀 |
| `minio` | 컨테이너끼리 `minio:9000`, 호스트에서 `localhost:9010` (콘솔 `9011`) |

VPN으로 VPC에 붙어 있으면 `.env`에서 `COMPOSE_PROFILES`를 비우고 `COMPOSE_DB_HOST=10.40.1.100`으로 둔다. `db-tunnel` 없이 VIP로 바로 붙는다.

### 노드 모니터링 대시보드

DB 노드 3대의 역할(Leader/Replica)·상태·복제 지연을 Grafana로 본다. `patronictl list`와 같은 내용을 2초마다 갱신한다. dev 스택과 따로 띄운다.

```bash
docker compose -f docker-compose.monitoring.yml up -d
# http://localhost:3030  첫 화면이 "OpenSQL HA 노드 현황" 대시보드다
docker compose -f docker-compose.monitoring.yml down
```

자료는 각 노드의 Patroni가 내보내는 지표(`8008/metrics`)다. 8008은 클러스터 안에서만 열려 있어 `patroni-tunnel` 컨테이너가 db-01을 거치는 SSH 터널로 가져온다. `.env`의 `SSH_*`를 dev 스택과 같이 쓰고, DB 서버에는 아무것도 설치하지 않는다.

| 화면 | 내용 |
|---|---|
| 맨 위 숫자 | Leader 노드, streaming Replica 수, 응답 노드 수, TL, 최대 Lag |
| 노드 현황 | 노드마다 멤버·IP·역할·상태·TL·Lag·Postgres 시작 시각 |
| 노드별 상태 변화 | 노드마다 상태가 바뀐 시점을 색 띠로 보여 준다 |
| Replica Lag | Replica마다 Leader보다 뒤처진 바이트 수 |

노드 3대는 고정 목록이다. Patroni를 멈추거나 서버가 꺼진 노드는 목록에서 빠지지 않고 빨간 `응답 없음`으로 남는다. `patronictl list`에는 `stopped`로 나오다가 30초쯤 뒤 줄이 사라진다. 역할·상태·Lag를 계산하는 규칙은 `monitoring/prometheus/rules.yml`에 있다.

Lag는 Leader가 쓴 WAL 위치에서 Replica가 받은 WAL 위치를 뺀 값이다. 노드마다 수집 시각이 최대 2초 어긋나므로 쓰기가 많을 때는 그만큼 크게 보일 수 있다.

대시보드는 `monitoring/grafana/dashboards/opensql-ha-nodes.json`이다. 화면에서 고친 내용은 저장되지 않으므로 JSON 파일을 고친다. 포트는 이 Mac에만 열린다.

---

## 컴포즈 파일

| 파일 | 용도 |
|---|---|
| `docker-compose.local.yml` | `local`: PostgreSQL·MinIO·server·worker. 테스트는 PostgreSQL·MinIO만 쓴다 |
| `docker-compose.dev.yml` | `dev`: DB 클러스터로 가는 SSH 터널·MinIO·server·worker |
| `docker-compose.monitoring.yml` | 노드 모니터링: Patroni 지표용 SSH 터널·Prometheus·Grafana |

---

## 프로파일 구성

`local`과 `dev`는 DB 위치, 마이그레이션 실행 방식, 암호화 알고리즘이 다르다. 애플리케이션 코드는 동일하다.

| 항목 | `local` | `dev` |
|---|---|---|
| DB | Docker PostgreSQL 17.8 + pgvector 0.8.1 | DB 클러스터 (Tmax OpenSQL v3.0 + pgvector 0.8.1) |
| 접속 경로 | 직접 | SSH 터널 → OpenProxy VIP |
| 접속 정보 | `application-local.yml` | `.env` |
| 연결 초기화 | 없음 | `SET SERVER ROLE TO 'default'` |
| 검색 연결 | 기본 연결을 함께 씀 | 별도 풀 `aidocs_ro` (Replica, 최대 8) |
| 암호화 알고리즘 | AES-256 | ARIA-256 |
| CORS 허용 | `application-local.yml` | `.env` |
| SQL 로깅 | ON | OFF |
| Flyway | 기동 시 자동 실행 | 수동 실행 |

---

## 암호화

`app_user.name`과 `document_version.original_filename`은 암호화해 저장한다. 알고리즘은 `app.encryption_cipher` 설정으로 정하고, 지정하지 않으면 ARIA-256을 쓴다.

ARIA는 Tmax OpenCrypto 1.0에만 있으므로 `dev`는 ARIA-256을, 로컬 PostgreSQL의 `pgcrypto`에는 없으므로 `local`은 AES-256을 쓴다. 로컬 컨테이너의 `pgcrypto`는 `scripts/local-db/00-extensions.sql`이 설치한다. 클러스터에는 `opencrypto` 확장이 사전에 설치되어 있어야 한다.

암호문에 알고리즘 정보가 들어 있어 `app_decrypt`는 어느 쪽으로 저장된 값이든 복호화한다. ARIA 전환 전에 저장된 AES-256 암호문도 그대로 읽힌다.

`DB_ENCRYPTION_KEY`는 트랜잭션을 열 때마다 그 트랜잭션에만 `app.encryption_key`로 설정된다. 따라서 암호화 컬럼을 읽고 쓰는 코드는 반드시 `@Transactional` 안에서 실행해야 한다. 키가 바뀌면 기존 암호문을 복호화할 수 없으므로 모든 인스턴스에 동일한 값을 설정한다.

---

## 환경 변수

`.env.dev.example`을 `.env`로 복사해 사용한다. gradle로 띄우는 `dev` 프로파일과 dev 스택이 같은 파일을 읽는다.

| 변수명 | 필수 | 설명 |
|---|:--:|---|
| `DB_HOST` | dev | DB 호스트. 터널을 거치므로 `127.0.0.1` |
| `DB_PORT` | dev | 포트. `TUNNEL_LOCAL_PORT`와 같은 값 (`15432`) |
| `DB_NAME` | dev | OpenProxy 풀 이름 `aidocs_session` |
| `DB_SEARCH_NAME` | X | 검색 연결의 OpenProxy 풀 이름 (기본 `aidocs_ro`) |
| `DB_USERNAME` | O | 접속 계정 `aidocs` |
| `DB_PASSWORD` | O | 접속 비밀번호 |
| `DB_ENCRYPTION_KEY` | O | 트랜잭션마다 `app.encryption_key`에 설정할 고엔트로피 Base64 키 |
| `DB_ENCRYPTION_CIPHER` | X | 암호화 알고리즘 (기본 `aria256`) |
| `DB_SOCKET_TIMEOUT` | X | 쿼리 응답 대기 초 (기본 60). 오래 걸리는 마이그레이션을 수동 실행할 때만 늘리거나 `0`(무제한)으로 둔다 |
| `SSH_HOST` | O | 터널을 붙일 서버 주소 (`db-01`, `13.124.25.248`) |
| `SSH_PORT` | X | SSH 포트 (기본 22) |
| `SSH_USER` | O | SSH 계정 (`rocky`) |
| `SSH_KEY_PATH` | O | pem 파일 경로. dev 스택은 절대경로만 받는다 |
| `TUNNEL_LOCAL_PORT` | X | `tunnel.sh`가 로컬에서 열 포트 (기본 15432) |
| `TUNNEL_REMOTE_HOST` | X | 서버에서 본 접속 주소 (기본 OpenProxy VIP `10.40.1.100`) |
| `TUNNEL_REMOTE_PORT` | X | 서버에서 본 접속 포트 (기본 `6432`) |
| `STORAGE_ENDPOINT` | dev | 호스트에서 본 MinIO 주소 (`http://localhost:9010`) |
| `STORAGE_REGION` | O | 리전. MinIO는 무시하지만 SDK가 서명에 쓰므로 값이 있어야 한다 |
| `STORAGE_ACCESS_KEY` | O | 액세스 키 |
| `STORAGE_SECRET_KEY` | O | 시크릿 키 |
| `STORAGE_BUCKET` | O | 원본 파일을 담을 버킷 |
| `OPENAI_API_KEY` | O | 임베딩(`text-embedding-3-small`, 1536차원)에 쓰는 OpenAI API 키 |
| `CORS_ALLOWED_ORIGINS` | O | CORS를 허용할 origin. 쉼표로 여러 개를 넘긴다 |
| `COHERE_API_KEY` | X | 검색 결과 재정렬. 없으면 RRF 순서 |
| `SERVER_HOST_PORT` · `MINIO_HOST_PORT` · `MINIO_CONSOLE_HOST_PORT` | X | dev 스택의 호스트 포트 (8080 / 9010 / 9011) |
| `GRAFANA_HOST_PORT` · `PROMETHEUS_HOST_PORT` | X | 모니터링 스택의 호스트 포트 (3030 / 9090) |
| `GRAFANA_ADMIN_PASSWORD` | X | Grafana `admin` 비밀번호 (기본 `admin`). 보기는 로그인 없이 된다 |
| `WORKER_IMAGE` | X | 워커 이미지 (기본 `ghcr.io/mash-up-kr/spr1n6-osscontest-worker:latest`) |

필수 칸의 `dev`는 gradle로 띄울 때만 필요하다는 뜻이다. dev 스택은 컨테이너 안의 값을 컴포즈가 정한다.

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
