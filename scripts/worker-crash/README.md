# Worker 비정상 종료 시나리오

작업 처리 중인 Worker가 비정상 종료되면, lease 만료 후 해당 미완료 작업을 다른 Worker가 선점하여 최종 완료할 수 있는지 확인하는 방법이다. Worker 1을 `SIGKILL`하고, DB의 자동 회수와 Worker 2의 인계를 순서대로 관찰한다.

[← scripts 목차](../README.md)

## 목차

1. [먼저 알아 둘 것](#1-먼저-알아-둘-것)
2. [준비물](#2-준비물)
3. [처음부터 따라 하기](#3-처음부터-따라-하기)
4. [설정과 결과 파일](#4-설정과-결과-파일)
5. [정리하기](#5-정리하기)
6. [막혔을 때](#6-막혔을-때)

---

## 1. 먼저 알아 둘 것

### 구성

| 파일 | 하는 일 |
|---|---|
| [`worker-lease-recovery.sh`](worker-lease-recovery.sh) | 두 Worker의 실행 상태를 제어하고, 같은 Job의 lease 만료·자동 회수·재선점·완료를 확인한다. JSON 결과와 Worker 로그를 저장한다 |

### 용어와 기대 결과

| 용어 | 뜻 |
|---|---|
| lease | Worker가 Job을 처리할 수 있는 기한. 실행 중에는 heartbeat로 연장한다 |
| 자동 회수 | lease가 만료된 `PROCESSING` Job을 DB의 `pg_cron` 회수 절차가 `PENDING`으로 돌리는 일. Worker가 직접 회수하지 않는다 |
| epoch | 실행권의 세대. 새 claim에서 Job과 문서의 `execution_epoch`가 함께 증가한다 |

**기대 결과:** 같은 Job이 `PROCESSING` → lease 만료 → `PENDING` → 다른 소유자의 `PROCESSING` → `COMPLETED`로 바뀐다. 마지막 `outcome`은 `INDEXED`이고, 청크가 한 개 이상 저장되며 청크 번호 중복이 없어야 한다.

### 주의

**지정한 Docker 컨테이너를 실제로 멈추고 Worker 1을 강제 종료한다.** 시험용 OpenSQL·OpenProxy·원문 저장소와 Worker 두 대를 준비한다. 이 DB 큐를 소비하는 Worker는 지정한 두 대뿐이어야 하고, 시험 중에는 문서 하나만 업로드한다. 기존 미종결 Job이 있으면 스크립트가 시작을 거절한다.

Worker 2는 시험 시작 때 정상 종료하고, `PENDING` 회수를 확인한 뒤 다시 시작한다. 회수 직후 바로 선점해 버려 관측을 놓치는 일을 막기 위한 순서다. Worker 1은 종료 상태로 남으며, 오류나 Ctrl+C에서도 스크립트가 멈춘 Worker 2는 재개를 시도한다.

DB는 조회만 한다. lease·상태를 직접 바꾸거나 회수 프로시저를 수동 호출하지 않는다. OpenSQL 장애는 별도의 [DB 장애 시나리오](../db-outage/README.md)로 시험한다.

---

## 2. 준비물

| 준비물 | 확인 | 용도 |
|---|---|---|
| Bash, Python 3 | `bash --version`, `python3 --version` | 실행 도우미, Python 표준 라이브러리 |
| Docker와 실행 중인 Worker 두 대 | `docker ps` | Worker 정상 종료·SIGKILL·재시작·로그 수집 |
| `psql` | `psql --version` | OpenProxy를 통한 DB 상태 조회 |
| 시험용 OpenSQL·OpenProxy | 아래 접속 확인 | 쓰기 가능한 Primary와 큐·청크 저장소 |
| API 서버·웹, 원문 저장소, 임베딩 API | 웹에서 문서 업로드 가능 | 새 INDEX Job 등록과 최종 인덱싱 |
| DB 자동 회수 설정 | `pg_cron`과 `reclaim_expired_indexing_jobs` 설정 확인 | 만료된 lease를 자동 회수 |

Server의 최신 마이그레이션이 적용된 환경을 쓴다. DB 스키마와 회수 프로시저는 Server가 소유하므로 이 스크립트가 설치하지 않는다. 일반 PostgreSQL에는 OpenProxy 전용 `SET SERVER ROLE` 문장이 없어 그대로 실행할 수 없다.

Worker 두 대는 같은 DB·원문 저장소를 사용하고, `INDEXING_WORKER_ID`는 서로 달라야 한다. 기본값인 실행별 UUID도 사용할 수 있다. `FAULT_INJECTION_ENABLED=false`로 두고, SIGKILL한 Worker가 외부 도구에 의해 자동으로 다시 생성되지 않게 한다. 정상 문서로 인덱싱이 되는 환경을 먼저 준비한다.

터미널은 두 개를 쓴다. **터미널 A**는 스크립트, **터미널 B**는 OpenProxy SSH 터널이다. 문서 업로드는 웹에서 한다. 터미널 A는 Bash를 사용하고 저장소 루트로 이동한다.

```bash
bash
cd "<이 저장소 경로>"
ls gradlew
```

---

## 3. 처음부터 따라 하기

### 3-1. OpenProxy 터널 열기 (터미널 B)

OpenProxy에 직접 접근할 수 없는 환경에서는 기존 접속 경로로 터널을 연다. 아래 `<...>` 값은 시험 환경에 맞게 바꾼다. 터널이 이미 열려 있으면 이 단계는 건너뛴다.

```bash
ssh -N -L "15432:<OpenProxy-VIP>:6432" -i "<SSH-키-경로>" "<SSH-사용자>@<접속-노드>"
```

이 터미널은 시험이 끝날 때까지 열어 둔다.

### 3-2. DB와 Worker 지정하기 (터미널 A)

```bash
export DB_HOST=localhost DB_PORT=15432 DB_USER=aidocs DB_NAME=aidocs DB_SCHEMA=public
export WORKER1_CONTAINER=aidocs-dev-worker-1
export WORKER2_CONTAINER=aidocs-dev-worker-2
read -r -s -p 'DB 비밀번호: ' PGPASSWORD; echo
export PGPASSWORD
```

위 비밀번호 입력은 Bash에서 실행한다. `~/.pgpass`를 사용하면 비밀번호 입력을 생략할 수 있다. Worker가 사용하는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`와 이 스크립트의 `DB_*` 변수 이름은 다르다. 접속 대상은 같게 맞춘다.

같은 psql 세션에서 role을 설정하고 Primary를 조회한다. `-c` 옵션 대신 표준 입력으로 두 문장을 함께 보낸다.

```bash
psql -X -w -qAt -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" <<'SQL'
SET SERVER ROLE TO 'default';
SELECT pg_is_in_recovery(), current_setting('transaction_read_only');
SQL
docker inspect --format '{{.Name}} running={{.State.Running}} paused={{.State.Paused}}' \
  "$WORKER1_CONTAINER" "$WORKER2_CONTAINER"
```

**기대 결과:** DB는 `f|off`, 두 Worker는 `running=true paused=false`다.

### 3-3. 시험 시작하기 (터미널 A)

```bash
bash scripts/worker-crash/worker-lease-recovery.sh
```

시작 안내의 두 Worker와 소비자 수를 확인한 뒤 Enter를 누른다. 스크립트는 Worker 2를 정상 종료하고 새 업로드를 기다린다. **`지금 웹에서 테스트 문서 하나를 새로 업로드하세요`가 나온 뒤** 충분한 분량의 문서를 하나 올린다. 업로드 전에는 기존 미종결 Job을 모두 끝내 둔다.

기본 종료 시점은 `EMBEDDING`이다. 이 단계를 너무 빨리 지나가면 처리 단계 제한을 풀 수 있다.

```bash
bash scripts/worker-crash/worker-lease-recovery.sh --kill-phase ANY
```

### 3-4. 진행과 성공 판정 보기

| 단계 | 화면·상태 | 뜻 |
|---|---|---|
| 1~2 | Primary·Worker 확인 후 업로드 대기 | Worker 1만 새 Job을 받을 수 있다 |
| 3 | Worker 1 SIGKILL, `after-kill.json` 저장 | 처리 중 종료됐고 Job은 기존 소유자의 `PROCESSING`으로 남는다 |
| 4 | lease 만료 후 `PENDING` | DB 시각으로 원래 lease 만료를 확인하고, 소유권이 해제된 회수를 관측한다 |
| 5 | Worker 2 시작, 새 소유자의 `PROCESSING` | 소유자가 다르고 epoch·시도 횟수가 증가한 새 실행권을 관측한다 |
| 6~7 | `COMPLETED`·`INDEXED`, 청크 검증 후 성공 | 인계한 실행과 완료한 실행의 epoch가 같고 결과가 저장됐다 |

완료 후에는 Job의 `worker_id`, `lease_until`, `next_retry_at`가 비어야 한다. 같은 `source_event_id`의 Job은 한 개여야 한다. lease 길이, cron 주기, 회수 유예시간, Worker 기동 시간에 따라 대기 시간이 달라진다.

---

## 4. 설정과 결과 파일

### 실행 옵션

```bash
bash scripts/worker-crash/worker-lease-recovery.sh --help
```

| 옵션 | 기본값 | 용도 |
|---|---|---|
| `--worker1`, `--worker2` | `WORKER1_CONTAINER`, `WORKER2_CONTAINER` 또는 위 예시 이름 | 강제 종료할 Worker와 인계할 Worker |
| `--kill-phase` | `KILL_PHASE` 또는 `EMBEDDING` | `ANY`, `DOWNLOADING`, `PARSING`, `CHUNKING`, `EMBEDDING` |
| `--poll-interval` | `0.25`초 | 상태 조회 간격 |
| `--upload-timeout` | `300`초 | 업로드 후 지정 처리 단계 관측 대기 |
| `--recovery-timeout` | `300`초 | SIGKILL 후 lease 만료·자동 회수 대기 |
| `--completion-timeout` | `600`초 | Worker 2의 재선점·완료 대기 |
| `--output-dir` | 현재 폴더의 `worker-recovery-<시각>-<PID>` | 새 결과 폴더. 이미 있으면 거절 |
| `--yes` | 사용 안 함 | 시작 전 Enter 확인 생략. 업로드 대기는 유지 |

`DB_HOST`, `DB_PORT`, `DB_USER`, `DB_NAME`, `DB_SCHEMA`의 기본값은 3-2의 예시와 같다.

### 결과 파일

| 파일 | 담는 내용 |
|---|---|
| `environment.json`, `summary.json` | 시험 대상·설정, 종료 코드·메시지, 이전·새 소유자 |
| `before-kill.json`, `after-kill.json` | 강제 종료 직전·직후의 Job |
| `recovered-pending.json`, `handoff.json`, `completed.json` | 자동 회수, 새 실행권, 최종 완료 |
| `timeline.jsonl` | DB 시각과 함께 기록한 단계별 Job 상태 |
| `result-checks.json` | 동일 이벤트 Job 수, 청크 수, 청크 번호 중복 수 |
| `worker1.log`, `worker2.log` | 시험 시작 이후 Worker 로그 |

도달한 단계까지만 파일이 생긴다. 종료 코드는 `0`=성공, `1`=실패·환경 오류, `2`=관측 부족·경합으로 판정 불가, `130`=중단이다. 결과 파일은 실행 기록이므로 소스와 함께 커밋하지 않는다.

---

## 5. 정리하기

종료 메시지와 컨테이너 상태를 확인한 뒤 Worker 1을 다시 시작한다.

```bash
docker start "$WORKER1_CONTAINER"
docker inspect --format '{{.Name}} running={{.State.Running}}' \
  "$WORKER1_CONTAINER" "$WORKER2_CONTAINER"
```

Worker 2 자동 재개가 실패했다면 `docker start "$WORKER2_CONTAINER"`도 실행한다. 터널을 이 시험을 위해 열었다면 터미널 B에서 Ctrl+C로 닫는다. DB의 Job·청크와 업로드 원문은 스크립트가 삭제하지 않는다.

## 6. 막혔을 때

| 증상 | 확인할 것 |
|---|---|
| Primary 접속 실패 또는 `SET SERVER ROLE` 오류 | SSH 터널·OpenProxy 경로·DB 자격 증명. 일반 PostgreSQL에 직접 붙지 않았는지 확인 |
| DB JSON 응답 해석 실패 | psql 표준 출력에 알림·프록시 메시지 등 JSON 외 문자열이 섞였는지 확인. 현재 스크립트는 순수 JSON 응답을 전제로 한다 |
| 미종결 Job이 있어 시작 거절 | 기존 작업이 끝날 때까지 기다린다. 다른 업로드와 소비자를 멈춘다 |
| 강제 종료 전에 종결됨 | 처리 시간이 긴 문서로 다시 시도하거나 `--kill-phase ANY` 사용 |
| 회수 시간 초과 | `pg_cron` 실행 기록·권한·회수 프로시저·lease 길이·Primary 회수 유예 확인. 필요하면 `--recovery-timeout`을 늘린다 |
| Worker 1이 다시 시작됨 | 자동 재시작·배포 도구의 컨테이너 재생성 여부 확인 |
| 완료했지만 재claim을 관측하지 못함 | 판정 불가다. 상태 조회 간격을 줄이거나 더 오래 처리되는 문서로 재시험 |
| Worker 2에서 완료되지 않음 | 고유한 worker-id, 장애 주입 설정, 원문 저장소·임베딩 API·breaker 상태와 로그 확인 |
