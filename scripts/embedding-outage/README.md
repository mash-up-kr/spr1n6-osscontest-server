# 임베딩 API 장애 시나리오

외부 임베딩 API가 일시 장애일 때 Worker가 재시도하고 서킷 브레이커(breaker)가 열렸다 닫히는 모습을 로컬에서 직접 보는 방법이다. Worker가 호출하는 임베딩 API를 이 폴더의 가짜 서버(스텁)로 바꿔 끼워 장애를 만든다. 운영 코드는 바꾸지 않는다. 1~7장은 로컬 격리 환경에서 사람이 화면을 보며 하는 시험이고, [8장](#8-자동-시험-429와-5xx-백오프-복구)은 이미 떠 있는 시험 환경에 붙어 429·5xx 재시도와 최종 완료를 스크립트가 판정한다.

[← scripts 목차](../README.md)

## 목차

1. [먼저 알아 둘 것](#1-먼저-알아-둘-것)
2. [준비물](#2-준비물)
3. [처음부터 따라 하기](#3-처음부터-따라-하기) — 환경 만들기부터 첫 시험까지
4. [모드별 시험](#4-모드별-시험)
5. [정리하기](#5-정리하기)
6. [막혔을 때](#6-막혔을-때)
7. [참고: 스텁 응답만 확인하기](#7-참고-스텁-응답만-확인하기)
8. [자동 시험: 429와 5xx 백오프 복구](#8-자동-시험-429와-5xx-백오프-복구) — 시험 환경에 붙어 PASS/FAIL로 판정

---

## 1. 먼저 알아 둘 것

### 구성

| 파일 | 하는 일 |
|---|---|
| [`embedding-stub.py`](embedding-stub.py) | 가짜 임베딩 API. 정해 둔 시간(장애 구간) 동안 오류를 돌려주고, 그 뒤에는 정상 응답(1536차원 벡터)을 돌려준다 |
| [`watch.py`](watch.py) | 스텁·breaker·Job 상태를 2초마다 색으로 다시 그리는 터미널 화면. DB는 읽기만 한다 |

### 용어

| 용어 | 뜻 |
|---|---|
| Job | 문서 하나를 인덱싱하는 작업. DB의 `indexing_job` 행이고, 상태는 `PENDING` → `PROCESSING` → `COMPLETED`다. 실패하면 `RETRY_WAIT`에서 기다렸다 다시 시도한다 |
| breaker | 외부 API가 계속 실패하면 호출 자체를 잠시 막는 장치. `CLOSED`(정상) → `OPEN`(차단, 60초) → `HALF_OPEN`(시험 호출 한 건) → 성공하면 `CLOSED`, 실패하면 다시 `OPEN` |
| 장애 구간 | 스텁을 띄운 시각부터 `--outage-seconds`(기본 90초) 동안. 이 안에서는 오류를, 지난 뒤에는 정상 응답을 돌려준다 |

### 주의

**breaker 행(`external_circuit_breaker`)은 같은 DB를 쓰는 모든 Worker가 공유한다.** 개발·운영 DB에서 이 시험을 하면 그 DB를 쓰는 Worker 전체가 영향을 받는다. 아래 [3장](#3-처음부터-따라-하기)은 이를 피하려고 **내 컴퓨터에 격리된 DB를 새로 만든다.** 공유 DB에서 하려면 다른 Worker를 모두 멈추고, breaker가 `CLOSED`인지 먼저 확인한다.

---

## 2. 준비물

| 준비물 | 확인 | 용도 |
|---|---|---|
| Docker Desktop (켜져 있어야 한다) | `docker info` | 임시 DB·MinIO, `watch.py`의 psql |
| Python 3 (3.11에서 확인) | `python3 --version` | 스텁, `watch.py` |
| JDK 21 | `java -version` | Worker 실행 |
| 이 저장소(worker)를 받은 상태 | 저장소 루트에서 `./gradlew --version` | Worker 실행 |
| `git`, `curl`, `openssl` | | 스키마 받기, 확인, 해시 계산 |

터미널은 4개를 쓴다. 이 문서는 **터미널 A**(준비·문서 올리기), **B**(Worker), **C**(진행 화면), **D**(스텁)로 부른다.

**B, C, D는 먼저 저장소 루트로 이동한 뒤 명령을 실행한다.** 아래 명령이 `./gradlew`와 `scripts/embedding-outage/...` 상대 경로를 쓰기 때문이다. `<경로>`는 이 저장소(worker)를 받은 폴더다.

```bash
cd <경로>          # 예: cd ~/project/spr1n6
ls gradlew         # gradlew 가 보이면 맞는 위치다
```

---

## 3. 처음부터 따라 하기

가장 흔한 `5xx` 모드로 끝까지 한 번 해 본다. 환경 준비에 약 3분(처음에는 이미지를 받느라 더), 시험에 약 3분이 걸린다.

### 3-1. 임시 DB 만들기 (터미널 A)

Worker의 DB 스키마는 API 서버 저장소가 소유한다. 서버 저장소의 마이그레이션 파일을 받아 임시 PostgreSQL에 적용한다.

```bash
export WORK=/tmp/embedding-outage-demo
mkdir -p "$WORK"
git clone --depth 1 https://github.com/mash-up-kr/spr1n6-osscontest-server.git "$WORK/server"
```

PostgreSQL을 띄우고 준비될 때까지 기다린다.

```bash
docker run -d --name fault-db -e POSTGRES_USER=worker -e POSTGRES_PASSWORD=worker \
  -e POSTGRES_DB=worker_integration_test -p 55434:5432 pgvector/pgvector:0.8.1-pg17
until docker exec fault-db pg_isready -U worker -d worker_integration_test >/dev/null 2>&1; do sleep 1; done
echo "DB 준비됨"
```

서버 마이그레이션이 쓰는 `pg_cron`을 설치하고 켠다. 설정을 반영하려고 DB를 한 번 재시작한다.

```bash
docker exec --user root fault-db sh -c 'apt-get update -qq && apt-get install -y -qq --no-install-recommends postgresql-17-cron' >/dev/null
docker exec fault-db sh -c "printf \"shared_preload_libraries = 'pg_cron'\ncron.database_name = 'worker_integration_test'\ncron.use_background_workers = on\n\" >> \"\$PGDATA/postgresql.conf\""
docker restart fault-db >/dev/null
until docker exec fault-db pg_isready -U worker -d worker_integration_test >/dev/null 2>&1; do sleep 1; done
echo "pg_cron 적용됨"
```

확장을 만들고 마이그레이션을 파일명 순서대로 적용한다.

```bash
docker exec fault-db psql -U worker -d worker_integration_test -v ON_ERROR_STOP=1 -q \
  -c "CREATE EXTENSION IF NOT EXISTS pgcrypto" -c "CREATE EXTENSION IF NOT EXISTS pg_cron"
for f in $(ls "$WORK"/server/src/main/resources/db/migration/*.sql | sort); do
  docker exec -i fault-db psql -U worker -d worker_integration_test -v ON_ERROR_STOP=1 -q < "$f" >/dev/null \
    || { echo "마이그레이션 실패: $f"; break; }
done
docker exec fault-db psql -U worker -d worker_integration_test -tAc \
  "SELECT 'breaker=' || state FROM external_circuit_breaker"
```

**기대 결과:** 마지막 줄에 `breaker=CLOSED`가 나온다. 나오지 않으면 [막혔을 때](#6-막혔을-때)를 본다.

### 3-2. 문서 보관소(MinIO) 만들기 (터미널 A)

Worker는 업로드된 문서를 MinIO(S3 호환 저장소)에서 읽는다.

```bash
docker run -d --name fault-minio -p 19100:9000 -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin minio/minio server /data
until curl -fs http://127.0.0.1:19100/minio/health/ready >/dev/null; do sleep 1; done
echo "MinIO 준비됨"
```

### 3-3. 문서를 올리는 도우미 만들기 (터미널 A)

문서를 올리는 일을 `add_docs`라는 함수로 만들어 둔다. `add_docs 1 5`는 1~5번 문서를 MinIO에 올리고 DB에 등록한다. 서버의 DB 트리거가 문서 버전마다 `PENDING` Job을 자동으로 만들므로 Job은 직접 넣지 않는다. 터미널 A에 아래 블록을 통째로 붙여 넣는다. 이 터미널을 닫으면 함수가 사라지니 다시 붙여 넣는다.

```bash
export WORK=/tmp/embedding-outage-demo
mcx() { docker run --rm -v "$WORK/files:/data" --add-host=host.docker.internal:host-gateway \
  --entrypoint sh minio/mc -c "mc alias set f http://host.docker.internal:19100 minioadmin minioadmin >/dev/null && $*"; }
psqlw() { docker exec -i fault-db psql -U worker -d worker_integration_test -v ON_ERROR_STOP=1 -q "$@"; }

add_docs() {  # 사용: add_docs <시작 번호> <끝 번호>
  mkdir -p "$WORK/files"
  mcx "mc mb --ignore-existing f/demo" >/dev/null
  psqlw -c "INSERT INTO tenant(name) SELECT 'demo' WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE name = 'demo')"
  for n in $(seq "$1" "$2"); do
    printf '데모 문서 %s번. 임베딩 장애 시험용 텍스트다 %s\n' "$n" "$RANDOM" > "$WORK/files/$n.txt"
    mcx "mc cp /data/$n.txt f/demo/docs/$n.txt" >/dev/null
    size=$(wc -c < "$WORK/files/$n.txt" | tr -d ' ')
    hash="sha256:$(openssl dgst -sha256 "$WORK/files/$n.txt" | awk '{print $NF}')"
    psqlw -v n="$n" -v size="$size" -v hash="$hash" <<'SQL'
WITH d AS (
  INSERT INTO document (tenant_id, owner_principal_id, title, latest_upload_version_no)
  SELECT id, 'demo', 'demo-' || :'n', 1 FROM tenant WHERE name = 'demo' ORDER BY id LIMIT 1
  RETURNING id
)
INSERT INTO document_version (document_id, version_no, embedding_version_no, source_object_key, original_filename,
                              mime_type, file_size, content_hash, created_by_principal_id)
SELECT id, 1, 1, 'docs/' || :'n' || '.txt', convert_to(:'n' || '.txt', 'UTF8'), 'text/plain', :size, :'hash', 'demo' FROM d;
SQL
    echo "문서 $n 등록"
  done
}
```

### 3-4. Worker 띄우기 (터미널 B)

일반 PostgreSQL에는 OpenProxy 전용 접속 문장(`SET SERVER ROLE ...`)이 없어 Worker가 접속에 실패한다. 그 값을 비우는 설정 파일을 먼저 만든다. 터미널 A에서 한 번만 실행한다.

```bash
cat > "$WORK/local-override.yml" <<'EOF'
spring:
  datasource:
    hikari:
      connection-init-sql: ""
EOF
```

터미널 B에서 저장소 루트로 가서 Worker를 띄운다. `OPENAI_API_KEY`는 가짜 값이고, `SPRING_AI_OPENAI_BASE_URL`이 스텁(`18081`)을 가리킨다. `SERVER_PORT`는 다른 서비스와 겹치지 않도록 바꾼 값이다.

```bash
DB_URL=jdbc:postgresql://localhost:55434/worker_integration_test DB_USERNAME=worker DB_PASSWORD=worker \
SPRING_CONFIG_ADDITIONAL_LOCATION=/tmp/embedding-outage-demo/local-override.yml \
OPENAI_API_KEY=fault-test SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:18081/v1 \
INDEXING_STORAGE_BUCKET=demo INDEXING_STORAGE_ENDPOINT=http://127.0.0.1:19100 INDEXING_STORAGE_REGION=us-east-1 \
AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin SERVER_PORT=18080 \
./gradlew bootRun
```

**기대 결과:** 처음에는 빌드가 돌고, 이어서 `Started WorkerApplicationKt in ... seconds`가 나온다. 이 줄이 나오면 기동이 끝난 것이다. 이 터미널은 계속 열어 둔다.

### 3-5. 진행 화면 켜기 (터미널 C)

저장소 루트에서 실행한다. 처음 실행하면 `postgres:17` 이미지를 받느라 시간이 걸린다. 스텁을 `18081`로 띄울 것이므로 `--stub-url`을 맞춘다.

```bash
DB_URL=jdbc:postgresql://localhost:55434/worker_integration_test DB_USERNAME=worker DB_PASSWORD=worker \
  python3 scripts/embedding-outage/watch.py --stub-url http://127.0.0.1:18081
```

**기대 결과:** 2초마다 화면이 갱신된다. 스텁이 아직 없으므로 첫 줄은 `스텁 응답 없음`이고, `breaker CLOSED`, `Job PENDING 0 ...`이 보인다.

### 3-6. 스텁 띄우기 (터미널 D)

저장소 루트에서 `5xx` 모드, 장애 구간 90초로 띄운다. **이 시각부터 90초가 장애 구간이다.**

```bash
python3 scripts/embedding-outage/embedding-stub.py --port 18081 --mode 5xx --outage-seconds 90
```

**기대 결과:** `stub mode=5xx outage=90.0s from=start port=18081`가 나온다. 터미널 C의 스텁 줄이 `mode=5xx 장애 구간 ████ NNs 남음`으로 바뀐다.

### 3-7. 문서 올리고 지켜보기 (터미널 A → C)

Worker의 동시 처리 수(기본 5)에 맞춰 문서 5개를 한꺼번에 올린다. 바로 터미널 C를 본다.

```bash
add_docs 1 5
```

터미널 C에서 아래 순서로 바뀐다. 시각은 환경에 따라 다르다.

| 시점 | 화면 | 뜻 |
|---|---|---|
| 올린 직후 | Job `PENDING 5` → 곧 `RETRY_WAIT 5` | Worker가 스텁을 호출했고 503을 받아 실패했다 |
| 직후 | breaker `OPEN`, `failures=5`, `열림 60s 남음`부터 카운트다운 | 5건이 연속 실패해 차단됐다. 스텁의 요청 수는 `503×5` |
| 약 1분 뒤 | breaker `HALF_OPEN`(노랑) 또는 다시 `OPEN`(빨강) | 시험 호출 한 건이 나갔다. 장애 구간 안이면 실패해 다시 `OPEN` |
| 장애 구간이 끝난 뒤 | breaker `CLOSED`(초록), Job `COMPLETED 5` | 시험 호출이 성공해 복구됐다. 최근 Job의 `오류/결과` 칸에 `INDEXED`가 보인다 |

전체가 끝나는 데 약 3분이 걸린다. 시험 호출이 장애 구간 안에서 실패하면 `failures`가 5보다 커지고 `OPEN`이 한 번 더 이어진다. 여기까지 보였다면 `5xx` 시험은 성공이다.

---

## 4. 모드별 시험

모드는 다섯 가지이고, 각각 **실제로 일어나는 서로 다른 장애**를 흉내 낸다. 같은 화면(터미널 C)에서 breaker와 Job이 어떻게 다르게 반응하는지 비교하는 것이 이 시험의 목적이다.

| 모드 | 흉내 내는 장애 | breaker | Job | 한 줄 요약 |
|---|---|---|---|---|
| [`5xx`](#5xx--api-서버-과부하점검) | API 서버 과부하·점검(503) | 5건 연속 실패하면 `OPEN` | `RETRY_WAIT` 후 자동 복구 | 일시 장애를 모아서 차단했다가 스스로 복구 |
| [`timeout`](#timeout--api가-응답하지-않음) | API가 응답하지 않음 | 5건 연속 실패하면 `OPEN` | `RETRY_WAIT` 후 자동 복구 | 응답 없음도 실패로 센다 |
| [`401`](#401--api-키가-잘못됨) | API 키 오류·만료 | **1건**으로 바로 `OPEN` | `RETRY_WAIT` | 설정 오류는 기다릴 필요 없이 바로 차단 |
| [`quota`](#quota--결제-한도-초과) | 결제 한도·크레딧 소진 | **1건**으로 바로 `OPEN` | `RETRY_WAIT` | 충전 전까지 다 실패하니 바로 차단 |
| [`429`](#429--요청이-너무-많음-속도-제한) | 속도 제한(요청 과다) | **열리지 않음** | `RETRY_WAIT`, 대기 시간이 `Retry-After` 이상 | API는 살아 있어서 차단 대신 Job만 기다린다 |

### 공통 순서

`5xx`는 3장에서 이미 해 봤다. 나머지 모드는 아래 네 단계를 반복한다. **Worker(B)와 진행 화면(C)은 계속 켜 둔다.**

1. **터미널 D** — 실행 중인 스텁을 Ctrl+C로 끈다.
2. **터미널 A** — [초기화](#모드를-바꾸기-전-초기화)를 한다. 이전 시험의 breaker 상태와 Job이 남아 있으면 결과가 섞인다.
3. **터미널 D** — 모드에 맞는 스텁을 띄운다. **이 시각부터 장애 구간이 시작된다.**
4. **터미널 A** — 스텁을 띄운 직후 문서를 올린다(`add_docs`). 문서 번호는 이전에 쓴 번호와 겹치지 않게 이어 쓴다. 늦게 올리면 장애 구간이 지나 장애 없이 끝난다.

그다음 터미널 C를 본다. 모든 복구는 자동이다. 사람이 재시도하거나 breaker를 닫지 않는다.

### 모드를 바꾸기 전 초기화

터미널 D의 스텁을 Ctrl+C로 끄고, 터미널 A에서 아래를 실행한다. **격리 DB 전용이다. 공유 DB에서는 실행하지 않는다.**

```bash
psqlw <<'SQL'
UPDATE external_circuit_breaker SET state = 'CLOSED', consecutive_failures = 0, opened_until = NULL, generation = generation + 1;
UPDATE document SET searchable_version_id = NULL;
DELETE FROM document_chunk;
DELETE FROM indexing_job;
DELETE FROM document_version;
DELETE FROM document;
SQL
```

---

### 5xx — API 서버 과부하·점검

**어떤 장애인가:** 임베딩 API 서버가 과부하이거나 점검 중이라 `503`을 돌려준다. 잠시 뒤 나아지는 일시 장애다.

**무엇을 보는가:** 실패가 쌓이면 breaker가 열려 API 호출을 막고, 시간이 지나면 한 건만 시험 호출해 살아났는지 확인한 뒤 스스로 닫히는 흐름 전체.

**실행:** 3장의 3-6, 3-7을 그대로 한다(스텁 `--mode 5xx --outage-seconds 90`, 문서 5개).

**화면에서 볼 것**

| 시점 | breaker | Job | 뜻 |
|---|---|---|---|
| 문서를 올린 직후 | `CLOSED` | `PENDING 5` → `RETRY_WAIT 5` | 5건이 모두 503을 받고 실패해 재시도를 기다린다. 스텁 요청 수 `503×5` |
| 직후 | **`OPEN`**, `failures=5`, `열림 60s 남음`부터 카운트다운 | `RETRY_WAIT 5` | 연속 5회 실패 기준에 닿아 차단됐다. 재시도 시각이 돼도 Job을 가져가지 않는다 |
| 약 1분 뒤 | `HALF_OPEN`(노랑) 또는 다시 `OPEN` | | 시험 호출이 한 건 나갔다. 장애 구간 안이면 실패해 `failures`가 6으로 늘고 다시 `OPEN` |
| 장애 구간이 끝난 뒤 | **`CLOSED`**(초록), `failures=0` | `COMPLETED 5`, 결과 `INDEXED` | 시험 호출이 성공해 복구됐다. 밀린 Job이 처리됐다 |

**왜 그렇게 되나:** 503은 "API가 지금 못 받는다"는 실패(`FAILURE`)로 분류되어 연속 실패 횟수(기본 5회)에 더해진다. 임계치에 닿으면 60초 동안 호출을 막아 이미 아픈 API를 더 두드리지 않는다.

**걸리는 시간:** 전체 약 3분.

---

### timeout — API가 응답하지 않음

**어떤 장애인가:** 네트워크가 끊겼거나 서버가 멈춰서 요청에 아무 응답이 없다. 오류 코드조차 오지 않아 클라이언트가 기다리다 타임아웃으로 포기한다.

**무엇을 보는가:** 오류 응답이 없어도 타임아웃을 실패로 세어 `5xx`와 똑같이 breaker가 열리는 것.

**실행:** 이 모드만 **Worker를 다시 띄워야 한다.** Worker의 기본 임베딩 타임아웃이 60초라 시험이 너무 길어지므로 `SPRING_AI_OPENAI_EMBEDDING_TIMEOUT=PT5S`(5초)를 더한다. 이 값은 스텁이 연결을 붙잡는 시간(`--hang-seconds`)보다 짧아야 Worker가 먼저 타임아웃을 낸다.

1. 공통 순서 1~2(스텁 끄기, 초기화)를 한다.
2. **터미널 B** — Worker를 Ctrl+C로 끄고, 저장소 루트에서 환경변수 한 줄을 더해 다시 띄운다. `Started WorkerApplicationKt ...`가 나올 때까지 기다린다.

   ```bash
   DB_URL=jdbc:postgresql://localhost:55434/worker_integration_test DB_USERNAME=worker DB_PASSWORD=worker \
   SPRING_CONFIG_ADDITIONAL_LOCATION=/tmp/embedding-outage-demo/local-override.yml \
   OPENAI_API_KEY=fault-test SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:18081/v1 \
   SPRING_AI_OPENAI_EMBEDDING_TIMEOUT=PT5S \
   INDEXING_STORAGE_BUCKET=demo INDEXING_STORAGE_ENDPOINT=http://127.0.0.1:19100 INDEXING_STORAGE_REGION=us-east-1 \
   AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin SERVER_PORT=18080 \
   ./gradlew bootRun
   ```

3. **터미널 D**와 **터미널 A**:

   ```bash
   python3 scripts/embedding-outage/embedding-stub.py --port 18081 --mode timeout --hang-seconds 30 --outage-seconds 150
   add_docs 9 13
   ```

**화면에서 볼 것:** 문서를 올리고 5초쯤 뒤 `RETRY_WAIT 5`, breaker `OPEN`(`failures=5`), 오류 칸 `OpenAIIoException`. 스텁 요청 수는 `hang×5`다. 이후 전이는 `5xx`와 같다.

**왜 그렇게 되나:** 타임아웃은 매핑된 예외가 아니라서 일반 실패(`FAILURE`)로 분류되어 `5xx`처럼 횟수에 더해진다.


---

### 401 — API 키가 잘못됨

**어떤 장애인가:** API 키가 틀렸거나 만료·폐기됐다. 키를 고치기 전까지는 어떤 요청도 성공하지 않는다.

**무엇을 보는가:** 이런 설정 오류는 5번 실패를 기다릴 이유가 없어서 **한 건만으로 바로** breaker가 열리는 것.

**실행:**

```bash
# 터미널 D
python3 scripts/embedding-outage/embedding-stub.py --port 18081 --mode 401 --outage-seconds 120
# 터미널 A (문서 1개)
add_docs 6 6
```

**화면에서 볼 것**

| 시점 | breaker | Job | 뜻 |
|---|---|---|---|
| 올린 직후 | **`OPEN`**, `failures=1` | `RETRY_WAIT 1`, 오류 칸 `EMBEDDING_AUTHENTICATION_FAILED` | 한 건 만에 열렸다. 스텁 요청 수 `401×1` |
| 약 1분 뒤 | `HALF_OPEN` 또는 다시 `OPEN` | | 시험 호출이 장애 구간 안이면 또 401을 받아 다시 `OPEN` |
| 장애 구간(120초)이 끝난 뒤 | **`CLOSED`** | `COMPLETED`, `INDEXED` | 키가 고쳐진 것처럼 정상 응답이 와서 복구됐다 |

Worker 로그(터미널 B)에는 `EMBEDDING_CALL_REJECTED`가 한 번 남는다.

**왜 그렇게 되나:** 401은 설정 오류(`CONFIGURATION`)로 분류되어 실패 횟수와 무관하게 바로 `OPEN`이 된다. 다만 Job은 영구 실패(`FAILED`)가 아니라 `RETRY_WAIT`이다. 키를 고치면 성공할 수 있어서 재시도 가능으로 본다.


---

### quota — 결제 한도 초과

**어떤 장애인가:** OpenAI 계정의 크레딧이 떨어지거나 결제 한도를 넘었다(429 `insufficient_quota`). 충전하기 전까지 모든 요청이 실패한다.

**무엇을 보는가:** 같은 429라도 "속도 제한"과 다르게 취급되어, `401`처럼 한 건 만에 breaker가 열리는 것.

**실행:**

```bash
# 터미널 D
python3 scripts/embedding-outage/embedding-stub.py --port 18081 --mode quota --outage-seconds 90
# 터미널 A (문서 1개)
add_docs 7 7
```

**화면에서 볼 것:** breaker가 `OPEN`, `failures=1`로 바뀌고 Job은 `RETRY_WAIT`에 오류 칸 `EMBEDDING_QUOTA_EXCEEDED`다. 복구되는 모습은 `401`과 같다.

**왜 그렇게 되나:** 응답 본문의 `code`가 `insufficient_quota`이면 모든 호출에 공통인 문제로 보고 설정 오류(`CONFIGURATION`)로 분류한다. 속도 제한(`rate_limit_exceeded`)과 본문으로 구분한다.


---

### 429 — 요청이 너무 많음 (속도 제한)

**어떤 장애인가:** 짧은 시간에 요청이 몰려 API가 "잠시 뒤에 다시 보내라(`Retry-After`)"고 한다. API는 멀쩡하다.

**무엇을 보는가:** 이 경우 **breaker가 열리지 않고**, 대신 Job 하나하나가 `Retry-After`만큼 기다렸다 다시 시도하는 것. 위 네 모드와 달리 **볼 곳이 breaker가 아니라 Job의 `다음 재시도` 칸**이다.

**실행:**

```bash
# 터미널 D
python3 scripts/embedding-outage/embedding-stub.py --port 18081 --mode 429 --outage-seconds 90
# 터미널 A (문서 1개)
add_docs 8 8
```

**화면에서 볼 것**

| 시점 | breaker | Job | 뜻 |
|---|---|---|---|
| 올린 직후 | **`CLOSED` 유지**, `failures=0` | `RETRY_WAIT 1`, 오류 칸 `RATE_LIMITED` | 429를 받았지만 breaker는 그대로다. 스텁 요청 수 `429×1` |
| 이어서 | `CLOSED` | `다음 재시도`가 30초 이상 뒤 | `Retry-After: 30`을 존중해 최소 그만큼 기다린다 |
| 시간이 되면 | `CLOSED` | `attempts` 증가 | 다시 시도한다. 장애 구간(90초) 안이면 또 429를 받아 반복한다 |
| 장애 구간이 끝난 뒤 | `CLOSED` | `COMPLETED`, `INDEXED` | 재시도가 성공했다 |

**왜 그렇게 되나:** 속도 제한(`RATE_LIMIT`)은 API 장애가 아니라 호출 속도 문제라서 실패 횟수에 더하지 않고 breaker 상태도 바꾸지 않는다. 호출 전체를 막는 대신 해당 Job의 다음 재시도 시각만 `Retry-After` 이상으로 미룬다. breaker가 열리는 경우는 `HALF_OPEN` 시험 호출이 429를 받았을 때뿐이다.


---

## 5. 정리하기

각 터미널에서 Ctrl+C로 Worker(B), 진행 화면(C), 스텁(D)을 끈다. 진행 화면이 쓰던 임시 컨테이너도 함께 지워진다. 그다음 터미널 A에서 아래를 실행한다.

```bash
docker rm -f fault-db fault-minio
rm -rf /tmp/embedding-outage-demo
```

---

## 6. 막혔을 때

| 증상 | 원인과 해결 |
|---|---|
| `no such file or directory: ./gradlew` 또는 `scripts/embedding-outage/...` 파일을 못 찾는다 | 저장소 루트가 아닌 곳에서 실행했다. `cd <경로>` 후 `ls gradlew`로 확인하고 다시 실행한다 |
| `Cannot connect to the Docker daemon` | Docker Desktop을 켠다 |
| `port is already allocated` | 해당 포트(`55434`, `19100`)를 쓰는 다른 프로세스를 끈다. 컨테이너가 남아 있으면 `docker rm -f fault-db fault-minio` |
| `debconf: delaying package configuration` | 3-1의 `pg_cron` 설치 중 나오는 경고다. 무시해도 된다 |
| `마이그레이션 실패: ...` | 출력된 파일의 오류를 읽는다. `pgcrypto`·`pg_cron` 확장 생성이 앞에서 실패했는지 본다 |
| Worker 로그에 `syntax error at or near "ROLE"` | [3-4](#3-4-worker-띄우기-터미널-b)의 `local-override.yml`을 만들지 않았거나 경로가 다르다. 환경변수로 비우면 안 되고 설정 파일로 비워야 한다 |
| Worker 로그에 `Connection is not available` | DB가 꺼졌거나 `DB_URL`의 포트가 다르다 |
| 진행 화면에 `DB 조회 실패` | 화면에 적힌 오류를 읽는다. 비밀번호·포트가 다르거나 마이그레이션이 적용되지 않은 경우가 많다 |
| 진행 화면에 `스텁 응답 없음` | 터미널 D의 스텁이 꺼져 있거나 `--stub-url`과 스텁 포트가 다르다. `--stub-url`의 기본값은 `http://127.0.0.1:8081`이다 |
| 문서를 올려도 Job이 `PENDING`에서 움직이지 않는다 | Worker(터미널 B)가 아직 기동 중이거나 꺼졌다. `Started WorkerApplicationKt`가 있는지 본다. breaker가 `OPEN`이면 열린 동안 Job을 가져가지 않는 것이 정상이다 |
| `5xx`인데 breaker가 열리지 않는다 | 올린 문서가 5개보다 적다. 임계치는 연속 실패 5회다 |

---

## 7. 참고: 스텁 응답만 확인하기

Worker나 DB 없이 스텁만 확인하려면 아래를 실행한다.

```bash
python3 scripts/embedding-outage/embedding-stub.py --port 18081 --mode 429 --outage-seconds 5 &
curl -si -X POST localhost:18081/v1/embeddings -d '{"input":["a","b"]}' | head -5   # 429 + Retry-After: 30
sleep 5
curl -s  -X POST localhost:18081/v1/embeddings -d '{"input":["a","b"]}' | head -c 120 # 정상 응답(벡터 2개)
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:18081/other -d '{}'         # 404
curl -s localhost:18081/stats                                                          # 모드·남은 시간·응답별 요청 수
kill %1
```

스텁 옵션은 `--port`(8081), `--outage-seconds`(90), `--mode`(5xx), `--hang-seconds`(10), `--outage-from`(start)이다. `--outage-from first-request`는 장애 구간을 기동 시각이 아니라 첫 임베딩 요청을 받은 시각부터 센다. `--fail-requests N`은 장애 시간 대신 처음 N개 요청만 실패시키고 이후에는 가짜 벡터를 반환한다. `--retry-after-seconds`(기본 30)는 429의 `Retry-After`를 지정한다. `timeout` 모드는 `--hang-seconds` 동안 응답이 없다가 연결이 끊긴다. Docker로 띄우려면 아래처럼 한다.

```bash
docker run --rm -p 8081:8081 -v "$PWD/scripts/embedding-outage/embedding-stub.py:/stub.py:ro" python:3-slim \
  python -u /stub.py --port 8081 --mode 5xx --outage-seconds 90
```

`watch.py` 옵션은 `--stub-url`(기본 `http://127.0.0.1:8081`, 환경변수 `STUB_URL`), `--interval`(2초), `--once`(한 번만 그리고 종료), `--no-color`다. Job 개수와 최근 Job은 시나리오로 만든 것만이 아니라 DB의 `indexing_job` 전체 기준이다. Ctrl+C나 SIGTERM으로 끝내면 임시 psql 컨테이너(`spr1n6-watch-psql`)가 지워지고, 강제 종료(SIGKILL)하면 남지만 다음 실행이 먼저 지운다.


---

## 8. 자동 시험: 429와 5xx 백오프 복구

임베딩 스텁이 최초 요청에만 429·503을 반환하고, Worker가 한 번 재시도해 가짜 벡터를 받아 같은 Job을 최종 완료하는지를 스크립트 하나가 PASS/FAIL로 판정한다. 3~4장과 달리 **환경을 만들지 않는다.** [`db-outage`](../db-outage/README.md), [`worker-crash`](../worker-crash/README.md)처럼 이미 떠 있는 시험 환경에 붙어 DB를 **읽기만** 하고, `docker`로 기존 Worker 컨테이너를 직접 다룬다. 서버의 compose 파일과 기존 Worker 설정은 바꾸지 않는다.

동작 원리는 이렇다. 스크립트가 기존 Worker 두 대의 이미지·환경변수·네트워크를 `docker inspect`로 읽고, 두 대를 시험 동안 멈춘다. 같은 이미지와 환경변수로 **임시 Worker** 1개(`embedding-test-worker`)를 만들되 `SPRING_AI_OPENAI_BASE_URL`을 **임시 스텁**(`embedding-test-stub`)으로 돌린다. 스텁은 같은 사용자 정의 네트워크 안에서 컨테이너 이름으로 닿고, 스텁 소스는 base64 인자로 넘겨 호스트 파일 마운트와 published port를 쓰지 않으므로 원격 Docker에서도 된다. 스텁 이미지가 없으면 Worker를 멈추기 전에 먼저 받는다. 모드마다 임시 Worker를 새로 만들고 재시도·큐 폴링 설정을 시험용으로 덮어쓴다. 시험이 끝나면(오류·Ctrl+C 포함) 임시 컨테이너를 지우고 **멈췄던 기존 Worker만** 다시 시작한다.

| 파일 | 하는 일 |
|---|---|
| [`embedding-backoff-recovery.sh`](embedding-backoff-recovery.sh) | 이 파일 하나로 실행한다(안에 파이썬 코드가 들어 있고, 스텁은 같은 폴더의 `embedding-stub.py`를 쓴다). 임시 스텁·임시 Worker를 띄우고, 사람이 올린 문서의 Job을 관측해 백오프·오류 코드·breaker·최종 완료를 판정한다 |

### 준비

| 준비물 | 확인 |
|---|---|
| Python 3, `psql` | `python3 --version`, `psql --version`. macOS에서 `brew install libpq`로 설치한 `psql`은 PATH에 연결되지 않지만, 스크립트가 Homebrew 경로(`/opt/homebrew/opt/libpq/bin` 등)를 찾아 쓴다 |
| `docker` | 스크립트를 실행하는 곳에서 `docker ps`로 기존 Worker 컨테이너(기본 `aidocs-dev-worker-1`, `aidocs-dev-worker-2`)가 보여야 한다. [`worker-crash`](../worker-crash/README.md)와 같은 방식이며 원격 Docker여도 된다. Worker는 사용자 정의 네트워크(compose 네트워크 등)에 연결돼 있어야 한다. 스텁용 이미지(기본 `python:3.12-slim`)는 Worker 이미지와 달리 서버에 없을 수 있다. 없으면 스크립트가 **Worker를 멈추기 전에** 한 번 받아 둔다(최대 10분). 이미지 저장소에 닿지 못하면 `--stub-image`로 서버에 이미 있는 Python 이미지를 지정한다 |
| OpenProxy SSH 터널 | 기본 `localhost:15432`. `ssh -N -L 15432:<OpenProxy-VIP>:6432 ...`로 미리 연다. 스크립트는 터널을 열지 않는다 |
| DB 비밀번호 | `~/.pgpass` 또는 `PGPASSWORD`. 스크립트에 적지 않는다 |
| 웹에서 문서 업로드 | API 서버·웹이 떠 있어야 한다 |

접속 환경변수는 `DB_HOST`(localhost), `DB_PORT`(15432), `DB_USER`(aidocs), `DB_NAME`(aidocs), `DB_SCHEMA`(public)이다. 일반 PostgreSQL(3장의 격리 DB)에 붙일 때만 `DB_SERVER_ROLE=`(빈 값)을 더해 OpenProxy 전용 `SET SERVER ROLE` 문장을 뺀다.

**주의:**

- 다른 시나리오(`db-outage`, `worker-crash`)를 모두 끝낸 뒤 **마지막에** 실행한다.
- 시험 동안 이 큐를 소비하는 **기존 Worker가 멈춘다.** 이 큐를 소비하는 Worker는 지정한 두 대뿐이어야 한다.
- `external_circuit_breaker` 행은 모든 Worker가 공유한다. 같은 DB를 쓰는 다른 시험이나 업로드와 **동시에 돌리지 않는다.** 시작 전에 미종결 Job이 없고 breaker가 `CLOSED`인지 점검하고, 아니면 시작하지 않는다.
- 정상 구간에도 스텁이 가짜 벡터를 돌려주므로 시험용 임베딩이 임시로 DB에 남는다.
- 중단·실패로 정리되지 않았을 때는 아래 명령으로 복구한다.

```bash
docker rm -f embedding-test-worker embedding-test-stub; docker start aidocs-dev-worker-1 aidocs-dev-worker-2
```

### 실행

저장소 루트에서 실행한다.

```bash
bash scripts/embedding-outage/embedding-backoff-recovery.sh --check-only   # DB·Worker 설정 점검과 스텁 이미지 받아 두기(Worker는 멈추지 않는다)
bash scripts/embedding-outage/embedding-backoff-recovery.sh                # 429 → 5xx 순서로 시험
bash scripts/embedding-outage/embedding-backoff-recovery.sh --modes 429    # 한 모드만
```

처음에는 `--check-only`로 DB 접속, Worker 실행 상태·이미지 일치, 스텁 이미지 받기를 먼저 확인하고, 이어서 `--modes 429`로 한 모드만 돌려 본 뒤 전체를 돌리는 순서를 권한다.

모드마다 `>>> 지금 웹에서 테스트 문서 하나를 업로드하세요 <<<`가 나오면 문서를 **하나만** 올린다. `--upload-command './upload.sh'`를 주면 그 명령으로 자동 업로드한다. 올린 뒤에는 Job 상태가 바뀔 때마다 한 줄씩 나오고, 스텁의 요청 로그(`docker logs -f`)가 같은 터미널에 섞여 나온다. 임시 Worker가 뜨기를 기다리는 데 처음에 최대 2분이 걸리고, Worker 준비 후에는 최초 오류와 약 10초의 재시도 대기, 가짜 벡터 처리 시간이 걸린다. 스텁이 한 건도 요청을 받지 못하면(임시 Worker가 스텁을 보지 못함) 판정하지 않고 종료 코드 2로 끝낸다.

### 판정

| 항목 | 기준 |
|---|---|
| 재시도 1회 관측 | 최초 시도 실패 후 `RETRY_WAIT` 한 번. 관측하지 못하면 `판정 불가`(종료 코드 2) |
| attempt_count | 최초 실패는 1, 완료 시 2(재시도 1회) |
| 백오프 간격 | 429는 `Retry-After: 10`으로 10초 뒤 재시도 예약. 5xx는 10초 기준 ±20% 지터로 8~12초 뒤 예약. 실제 실행에는 큐 폴링(1초)·처리 지연이 더해질 수 있다. DB 관측은 폴링 간격 + 2초 오차를 허용한다 |
| 오류 코드 | 429는 `RATE_LIMITED`와 breaker `CLOSED` 유지. 5xx는 오류 코드가 비어 있지 않다 |
| 스텁 응답 | 모드별 최초 요청만 429 또는 503을 반환하고, 이후에는 1536차원 가짜 벡터를 반환한다. 오류 응답 정확히 1회와 200 응답을 확인한다 |
| 최종 | 같은 Job이 `COMPLETED`, `outcome=INDEXED`, 총 시도 2회 |

5xx에서 breaker 상태는 화면에 보이기만 하고 판정하지 않는다. 이 시험에서는 최초 요청 한 건만 실패시키기 때문이다. breaker `OPEN` 전이는 4장의 `5xx` 시험(문서 5개)으로 본다.

종료 코드: 0=모두 PASS, 1=FAIL·환경 오류, 2=관측 부족으로 판정 불가, 130=중단.

### 설정

| 옵션·환경변수 | 기본 | 뜻 |
|---|---|---|
| 임시 Worker 재시도 | 429: PT8S / 5xx: PT10S | 429는 지터 상한이 9.6초이므로 `Retry-After: 10`이 항상 우선한다. 5xx는 8~12초가 된다. 기존 Worker의 환경변수와 무관하게 시험용 Worker에만 적용한다 |
| 임시 Worker 큐 폴링 | PT1S | 재시도 예약이 만료되면 최대 약 1초 안에 다시 탐색한다 |
| 임시 Worker 최대 시도 | 2 | 최초 시도와 재시도 1회까지만 허용한다 |
| `WORKER1_CONTAINER` (`--worker1`) | aidocs-dev-worker-1 | 시험 동안 멈출 기존 Worker 1. 이 Worker의 이미지·환경변수·네트워크를 임시 Worker에 복사한다 |
| `WORKER2_CONTAINER` (`--worker2`) | aidocs-dev-worker-2 | 시험 동안 멈출 기존 Worker 2. 1과 이미지가 같아야 한다 |
| `STUB_IMAGE` (`--stub-image`) | python:3.12-slim | 스텁을 실행할 이미지. `python` 명령이 있으면 된다. 없으면 시작 전에 받는다 |
| `--poll-interval` | 1 | DB 폴링 간격(초). 조회마다 새 접속을 만들고 OpenProxy를 다른 사람도 쓰므로 너무 짧게 하지 않는다 |
