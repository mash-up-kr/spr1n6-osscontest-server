# OpenSQL 장애 시나리오

작업 처리 중 OpenSQL이 중단되었다가 복구되어도 저장된 작업 상태를 기반으로 미완료 작업의 처리를 재개하여 최종 완료할 수 있는지 확인하는 방법이다. 현재 Primary의 Patroni를 강제 종료하고, OpenProxy를 통한 DB 연결 실패·복구와 같은 Job의 최종 완료를 관찰한다.

[← scripts 목차](../README.md)

## 목차

1. [먼저 알아 둘 것](#1-먼저-알아-둘-것)
2. [준비물](#2-준비물)
3. [처음부터 따라 하기](#3-처음부터-따라-하기)
4. [설정과 성공 판정](#4-설정과-성공-판정)
5. [정리하기](#5-정리하기)
6. [막혔을 때](#6-막혔을-때)

---

## 1. 먼저 알아 둘 것

### 구성

| 파일 | 하는 일 |
|---|---|
| [`db-job-recovery.sh`](db-job-recovery.sh) | 새 Job의 `PROCESSING`을 관측하고 Primary Patroni를 SIGKILL한다. DB 접속 실패·쓰기 경로 복구 후 같은 Job의 `COMPLETED`를 기다린다 |

### 용어와 기대 결과

| 용어 | 뜻 |
|---|---|
| OpenProxy | Worker와 psql이 DB에 접근하는 경로. 세션마다 `SET SERVER ROLE TO 'default'`로 쓰기 대상을 지정한다 |
| Primary | 현재 쓰기를 받는 DB 노드. Patroni의 `/cluster`에서 leader 이름을 조회한다 |
| 쓰기 경로 복구 | OpenProxy로 접속해 `pg_is_in_recovery()=false`, `transaction_read_only=off`를 확인한 상태 |

**기대 결과:** 새 Job의 `PROCESSING` 관측 → 현재 Primary Patroni SIGKILL → 실제 DB 조회 실패 → 쓰기 가능한 DB 접속 복구 → 같은 Job의 `COMPLETED`다. Worker는 시험 내내 실행 상태를 유지한다.

### 주의

**현재 Primary의 Patroni를 실제로 강제 종료하고 systemd 자동 재시작을 막는다.** 격리된 시험용 OpenSQL·OpenProxy·Patroni 환경에서 실행한다. 다른 시험이나 운영 작업과 겹치지 않게 하고, 문서는 하나만 업로드한다. 기존 미종결 Job이 있으면 시작하지 않는다.

장애 주입은 Patroni 프로세스에 대한 `SIGKILL`이다. DB 노드 전체 정지나 OpenSQL 클러스터 전체 중단을 주입하는 스크립트는 아니다. 이 조치로 실제 DB 조회 실패가 발생했는지 별도로 관측하며, 관측하지 못하면 성공으로 처리하지 않는다.

장애 주입 후 승격 대상과 DB 복구는 환경의 HA 설정에 맡긴다. 기존 Primary의 Patroni와 `Restart=no` 설정은 자동으로 되돌리지 않으므로 [5장](#5-정리하기) 절차를 따른다. Worker 비정상 종료는 별도의 [Worker 종료 시나리오](../worker-crash/README.md)로 시험한다.

---

## 2. 준비물

| 준비물 | 확인 | 용도 |
|---|---|---|
| Bash, Python 3 | `bash --version`, `python3 --version` | 실행 도우미, psql 응답·시간 제한 처리 |
| `psql`, `ssh` | `psql --version`, `ssh -V` | DB 조회, Patroni 노드 접속 |
| 시험용 OpenSQL·OpenProxy·Patroni | 아래 접속 확인 | DB 장애 주입과 자동 복구 |
| Patroni 노드용 SSH 키·권한 | 각 노드 SSH 접속 | 원격 `curl`, 비대화형 `sudo systemctl`·drop-in 파일 생성 |
| 실행 중인 Worker | 배포 환경의 로그·상태 확인 | DB 복구 후 미완료 작업 재개 |
| API 서버·웹, 원문 저장소, 임베딩 API | 웹에서 문서 업로드 가능 | 새 INDEX Job과 인덱싱 완료 |

Server의 최신 마이그레이션과 회수 설정이 적용된 환경을 쓴다. Worker가 쓰는 DB 접속 경로도 같은 OpenProxy를 통과해야 한다. 일반 PostgreSQL에는 OpenProxy 전용 `SET SERVER ROLE` 문장이 없어 그대로 실행할 수 없다.

Worker의 `FAULT_INJECTION_ENABLED=false`를 확인하고, 원문 저장소와 임베딩 API가 정상인 환경을 준비한다. DB 장애 동안 Worker를 수동으로 재시작하거나 다른 Job을 넣지 않는다.

터미널은 두 개를 쓴다. **터미널 A**는 스크립트·결과 기록, **터미널 B**는 OpenProxy SSH 터널이다. 문서 업로드는 웹에서 한다. 터미널 A는 Bash를 사용하고 저장소 루트로 이동한다.

```bash
bash
cd "<이 저장소 경로>"
ls gradlew
```

---

## 3. 처음부터 따라 하기

### 3-1. OpenProxy 터널 열기 (터미널 B)

기존 접속 경로로 터널을 연다. 아래 `<...>` 값은 시험 환경에 맞게 바꾼다. 이미 터널이 열려 있으면 이 단계는 건너뛴다.

```bash
ssh -N -L "15432:<OpenProxy-VIP>:6432" -i "<SSH-키-경로>" "<SSH-사용자>@<접속-노드>"
```

터널은 시험이 끝날 때까지 열어 둔다.

### 3-2. DB·SSH 접속 정보 지정하기 (터미널 A)

```bash
export DB_HOST=localhost DB_PORT=15432 DB_USER=aidocs DB_NAME=aidocs DB_SCHEMA=public
read -r -s -p 'DB 비밀번호: ' PGPASSWORD; echo
export PGPASSWORD

export SSH_KEY="$HOME/.ssh/opensql.pem"
export SSH_USER=rocky
export DB1='<postgresql1의 SSH 접속 주소>'
export DB2='<postgresql2의 SSH 접속 주소>'
export DB3='<postgresql3의 SSH 접속 주소>'
export PATRONI_API_PORT=8008
```

`~/.pgpass`를 사용하면 비밀번호 입력을 생략할 수 있다. `DB1`·`DB2`·`DB3`는 DB 쿼리 접속 주소가 아니라 **각 Patroni 노드의 SSH 주소**다. 기본값에 기대지 말고 세 주소를 실제 환경에 맞게 지정한다. 스크립트의 기존 기본값에는 `DB1`과 `DB3`가 같은 주소로 들어 있다.

`DB3`는 `/cluster` 조회용 접속점이다. 반환되는 이름 `postgresql1`, `postgresql2`, `postgresql3`와 위 주소 매핑이 일치해야 한다. 다른 이름을 쓰는 클러스터는 현재 스크립트로 실행할 수 없다.

### 3-3. 접속 확인하기 (터미널 A)

같은 psql 세션에서 role을 설정하고 Primary를 조회한다. `-c` 옵션 대신 표준 입력으로 두 문장을 함께 보낸다.

```bash
psql -X -w -qAt -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" <<'SQL'
SET SERVER ROLE TO 'default';
SELECT pg_is_in_recovery(), current_setting('transaction_read_only'), host(inet_server_addr());
SQL
ssh -i "$SSH_KEY" -o ConnectTimeout=8 -o BatchMode=yes "$SSH_USER@$DB3" \
  "curl -fsS -m 3 localhost:${PATRONI_API_PORT}/cluster"
```

**기대 결과:** DB 응답은 `f|off|<서버 주소>`다. `/cluster`에는 유일한 leader가 있고 이름이 위 노드 매핑에 포함된다. 각 노드에 SSH 접속할 수 있어야 하며, 장애 대상 노드에서는 비밀번호 입력 없이 필요한 `sudo` 명령을 실행할 수 있어야 한다.

### 3-4. 시험 시작과 문서 업로드 (터미널 A)

이 스크립트는 별도의 `--help` 옵션이 없다. 접속 확인 후 실행하면 장애 주입 절차가 시작된다. 결과는 터미널에 출력하므로 파일로 남기려면 `tee`를 함께 쓴다.

```bash
set -o pipefail
bash scripts/db-outage/db-job-recovery.sh 2>&1 | tee /tmp/db-outage-demo.log
result=${PIPESTATUS[0]}
echo "시험 종료 코드: $result"
```

`준비되면 ENTER를 누른 뒤 웹에서 ... 업로드하세요` 안내가 나오면 Enter를 누르고 문서 하나를 새로 업로드한다. `PROCESSING`을 관측할 만큼 충분한 분량을 사용한다. 이후에는 추가 확인 없이 Primary의 Patroni를 강제 종료한다.

### 3-5. 진행과 성공 판정 보기

| 단계 | 화면·상태 | 뜻 |
|---|---|---|
| 1 | 쓰기 가능한 Primary 확인 | 현재 OpenProxy 접속 경로가 정상이다 |
| 2~3 | 새 Job 발견, `PROCESSING` | 미완료 작업의 Job ID·소유자·시도 횟수·lease를 기록한다 |
| 4 | `장애 주입 대상: <leader> (<IP>)`, DB 장애 관측 | 기존 Primary의 Patroni를 SIGKILL했고 실제 DB 조회가 실패했다 |
| 5 | `DB 쓰기 경로 복구` | OpenProxy로 쓰기 가능한 DB를 다시 조회했다 |
| 6 | 같은 Job의 `COMPLETED`, 마지막 `PASS` | 새 Job을 만들지 않고 기존 Job의 최종 완료를 확인했다 |

**장애 주입 대상 이름과 IP를 기록해 둔다.** 시험을 중단하거나 실패해도 원복에 필요하다. `tee`로 실행했다면 `/tmp/db-outage-demo.log`에 남는다.

---

## 4. 설정과 성공 판정

### 환경변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `DB_HOST`, `DB_PORT` | `localhost`, `15432` | OpenProxy 쿼리 접속 경로 |
| `DB_USER`, `DB_NAME`, `DB_SCHEMA` | `aidocs`, `aidocs`, `public` | 조회 계정·DB·스키마 |
| `SSH_KEY`, `SSH_USER` | `~/.ssh/opensql.pem`, `rocky` | Patroni 노드 SSH 접속 |
| `DB1`, `DB2`, `DB3` | 기존 시험 환경 주소. 실행 전 세 값 모두 지정 | Patroni 노드 이름과 SSH 주소 매핑 |
| `PATRONI_API_PORT` | `8008` | 원격 노드의 Patroni API 포트 |
| `UPLOAD_TIMEOUT` | `300` | 새 Job 탐색 반복 횟수 |
| `PROCESSING_TIMEOUT` | `300` | 처리 중 상태 관측 반복 횟수 |
| `OUTAGE_TIMEOUT` | `180` | DB 조회 실패 관측 반복 횟수 |
| `RECOVERY_TIMEOUT` | `300` | 쓰기 경로 복구 관측 반복 횟수 |
| `COMPLETION_TIMEOUT` | `600` | 최종 완료 관측 반복 횟수 |
| `POLL_INTERVAL` | `1`초 | 조회 사이 대기 시간 |

`*_TIMEOUT`은 기존 코드에서 반복 횟수로 사용한다. 기본 조회 간격은 1초지만, psql·SSH 호출 시간도 더해지므로 실제 경과시간의 엄격한 상한은 아니다. `POLL_INTERVAL`을 바꾸면 전체 대기 시간도 달라진다.

### 판정 범위

성공하려면 장애 주입 전 `PROCESSING`, 주입 후 실제 DB 조회 실패, 쓰기 경로 복구, **같은 Job ID의 `COMPLETED`**를 모두 관측해야 한다. DB 조회 실패를 놓쳤거나 주입 전에 완료됐다면 종료 코드 `2`로 재시험을 요구한다. 최종 `FAILED`, 복구·완료 시간 초과 등은 `1`, 성공은 `0`이다.

`RETRY_WAIT 관측`, `재선점 정황 관측`은 출력용 참고 정보다. Worker는 기존 실행권으로 재개할 수도 있어 두 값이 `0`이어도 완료 조건을 충족하면 성공한다. 재선점 여부, 승격 노드의 동일성, `INDEXED` 결과·청크 무결성은 이 스크립트의 필수 판정에 포함되지 않는다. 외부 API 호출의 중복 방지도 검증하지 않는다.

DB는 조회만 하며 Job 상태를 직접 수정하지 않는다. 자동 JSON 결과 폴더나 Worker 로그 수집 기능은 없으므로 위 터미널 기록과 배포 환경의 Worker 로그를 함께 확인한다.

---

## 5. 정리하기

스크립트가 출력한 **장애 주입 대상 노드**에 SSH로 접속한다. 아래 `<장애-주입-IP>`는 기록한 IP로 바꾼다. 자동 승격된 새 Primary를 대상으로 실행하지 않는다.

```bash
ssh -i "$SSH_KEY" -o ConnectTimeout=8 -o BatchMode=yes "$SSH_USER@<장애-주입-IP>"
```

해당 노드에서 시험이 만든 drop-in을 지우고 Patroni를 다시 시작한다.

```bash
sudo rm -f /run/systemd/system/patroni.service.d/norestart.conf
sudo systemctl daemon-reload
sudo systemctl start patroni
sudo systemctl status patroni --no-pager
```

시험 전에 같은 경로의 drop-in이 있었다면 원래 내용으로 복원한다. 스크립트는 이 파일을 덮어쓰므로 기존 파일이 있는 환경에서는 실행 전에 내용을 별도로 보관한다. 이후 클러스터 합류 상태는 해당 환경의 절차로 확인한다. 원래 Primary로 강제 전환하는 작업은 포함하지 않는다.

Worker는 스크립트가 멈추지 않는다. 터널을 이 시험을 위해 열었다면 터미널 B에서 Ctrl+C로 닫는다. DB의 Job·청크와 업로드 원문은 스크립트가 삭제하지 않는다.

## 6. 막혔을 때

| 증상 | 확인할 것 |
|---|---|
| 쓰기 가능한 Primary 연결 실패 | SSH 터널·OpenProxy·DB 자격 증명. Worker의 DB 경로도 같은지 확인 |
| 현재 Primary를 찾지 못함 | `DB3` SSH 주소, Patroni API 포트, `/cluster` 응답의 유일한 leader 확인 |
| 알 수 없는 DB 노드 | Patroni 이름이 `postgresql1/2/3`인지, `DB1/2/3` 매핑이 맞는지 확인 |
| SSH·sudo 실패 | 키·사용자·노드 주소, 비대화형 sudo 권한 확인. 일부 명령이 실행됐을 수 있으므로 대상 노드 상태도 확인 |
| DB 연결 실패를 관측하지 못함 | Patroni 종료가 SQL 연결 장애로 이어졌는지, 복구가 조회보다 빨랐는지 확인. 성공 판정 없이 원복 후 재시험 |
| 장애 주입 전에 Job이 완료됨 | 더 오래 처리되는 문서로 재시험 |
| 복구·완료 시간 초과 | OpenProxy·Patroni 상태, Worker DB 대기 로그, 원문 저장소·임베딩 API·breaker 확인 |
| 시험 실패·Ctrl+C 중단 | 장애 주입 이후라면 5장 원복을 수행한다. 자동 원복은 없다 |
