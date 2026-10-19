#!/bin/bash
# OpenSQL 장애 시나리오. 실행 방법은 같은 폴더의 README.md에 있다.
# 처리 중인 Job을 확인한 뒤 현재 Primary의 Patroni를 SIGKILL한다.
# OpenProxy 접속 실패와 쓰기 경로 복구를 관측하고, 같은 Job의 COMPLETED를 기다린다.
# Worker는 멈추지 않는다. 기존 Primary의 Patroni와 Restart=no 설정은 시험 후 수동으로 되돌린다.
# localhost:15432에서 OpenProxy VIP:6432로 연결하는 SSH 터널을 미리 열어 둔다.
set -uo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-15432}"
DB_USER="${DB_USER:-aidocs}"
DB_NAME="${DB_NAME:-aidocs}"
DB_SCHEMA="${DB_SCHEMA:-public}"
UPLOAD_TIMEOUT="${UPLOAD_TIMEOUT:-300}"
PROCESSING_TIMEOUT="${PROCESSING_TIMEOUT:-300}"
OUTAGE_TIMEOUT="${OUTAGE_TIMEOUT:-180}"
RECOVERY_TIMEOUT="${RECOVERY_TIMEOUT:-300}"
COMPLETION_TIMEOUT="${COMPLETION_TIMEOUT:-600}"
POLL="${POLL_INTERVAL:-1}"

# 장애를 주입할 Patroni 노드의 SSH 접속 정보다. 실제 노드 매핑은 실행 전에 환경변수로 지정한다.
SSH_KEY="${SSH_KEY:-$HOME/.ssh/opensql.pem}"
SSH_USER="${SSH_USER:-rocky}"
DB1="${DB1:-13.124.25.248}"
DB2="${DB2:-13.125.16.16}"
DB3="${DB3:-13.124.25.248}"
PATRONI_API_PORT="${PATRONI_API_PORT:-8008}"

[[ "$DB_SCHEMA" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || { echo "DB_SCHEMA 형식 오류"; exit 1; }
command -v psql >/dev/null || { echo "psql 명령이 필요합니다."; exit 1; }

log() { echo "[$(date '+%H:%M:%S')] $*"; }

ssh_node() {
  ssh -i "$SSH_KEY" -o ConnectTimeout=8 -o BatchMode=yes "$SSH_USER@$1" "$2"
}

node_ip() {
  case "$1" in
    postgresql1) echo "$DB1" ;;
    postgresql2) echo "$DB2" ;;
    postgresql3) echo "$DB3" ;;
    *) return 1 ;;
  esac
}

# Patroni의 /cluster에서 유일한 leader 이름을 찾는다. DB3를 조회용 접속점으로 쓴다.
current_primary() {
  ssh_node "$DB3" "curl -s -m 3 localhost:${PATRONI_API_PORT}/cluster" 2>/dev/null | python3 -c '
import json, sys
try:
    d=json.load(sys.stdin)
    leaders=[m.get("name") for m in d.get("members",[]) if m.get("role") in ("leader","primary","master")]
    print(leaders[0] if len(leaders)==1 else "")
except Exception:
    print("")
'
}

kill_current_primary() {
  local leader ip
  leader=$(current_primary) || true
  [ -n "$leader" ] || { echo "Patroni에서 현재 Primary를 찾지 못했습니다." >&2; return 1; }
  ip=$(node_ip "$leader") || { echo "알 수 없는 DB 노드: $leader" >&2; return 1; }
  log "장애 주입 대상: $leader ($ip)"
  # systemd가 바로 다시 띄우지 못하게 Restart=no를 적용한 뒤 Patroni를 SIGKILL한다.
  ssh_node "$ip" 'sudo mkdir -p /run/systemd/system/patroni.service.d && printf "[Service]\nRestart=no\n" | sudo tee /run/systemd/system/patroni.service.d/norestart.conf >/dev/null && sudo systemctl daemon-reload && sudo systemctl kill -s KILL patroni'
  log "Primary Patroni SIGKILL 완료: $leader"
}

# OpenProxy는 연결마다 쓰기 대상(Primary)을 지정한다. 새 psql 세션의 첫 문장으로 보내야 한다.
# role 설정과 조회를 같은 표준 입력으로 보내고, 접속·응답 대기 시간에 상한을 둔다.
_psql_stdin() {
  local timeout="$1"
  local sql="$2"
  PSQL_SQL="$sql" PSQL_TIMEOUT="$timeout" \
  DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
  python3 - <<'PYSQL'
import os
import subprocess
import sys

command = [
    'psql', '-X', '-w', '-qAt', '-v', 'ON_ERROR_STOP=1',
    '-h', os.environ['DB_HOST'], '-p', os.environ['DB_PORT'],
    '-U', os.environ['DB_USER'], '-d', os.environ['DB_NAME'],
]
sql = "SET SERVER ROLE TO 'default';\n" + os.environ['PSQL_SQL']
env = os.environ.copy()
env.setdefault('PGCONNECT_TIMEOUT', '5')
try:
    proc = subprocess.run(
        command,
        input=sql,
        text=True,
        capture_output=True,
        timeout=float(os.environ['PSQL_TIMEOUT']),
        env=env,
    )
except subprocess.TimeoutExpired:
    print('psql timeout', file=sys.stderr)
    sys.exit(124)
if proc.stdout:
    sys.stdout.write(proc.stdout)
if proc.returncode != 0:
    if proc.stderr:
        sys.stderr.write(proc.stderr)
    sys.exit(proc.returncode)
PYSQL
}

q() {
  _psql_stdin 12 "$1"
}

q_try() {
  _psql_stdin 6 "$1" 2>/dev/null
}

primary_health() {
  q_try "SELECT pg_is_in_recovery()::text || '|' || current_setting('transaction_read_only') || '|' || COALESCE(host(inet_server_addr()),'local');"
}

log "[1/6] OpenProxy를 통한 writable Primary 연결 확인"
HEALTH=$(primary_health) || {
  echo "현재 OpenProxy를 통해 writable DB에 연결할 수 없습니다. 터널/OpenProxy/DB 상태를 확인하세요."
  exit 1
}
IFS='|' read -r RECOVERY READONLY SERVER <<< "$HEALTH"
if [ "$RECOVERY" != false ] || [ "$READONLY" != off ]; then
  echo "현재 연결이 writable Primary가 아닙니다: recovery=$RECOVERY read_only=$READONLY server=$SERVER"
  exit 1
fi
log "Primary 연결 OK: $SERVER"

UNFINISHED=$(q "SELECT count(*) FROM ${DB_SCHEMA}.indexing_job WHERE status IN ('PENDING','PROCESSING','RETRY_WAIT');") || exit 1
[ "$UNFINISHED" = 0 ] || { echo "기존 미종결 Job ${UNFINISHED}건이 있습니다. 모두 끝난 뒤 실행하세요."; exit 1; }
BASE=$(q "SELECT COALESCE(max(id),0) FROM ${DB_SCHEMA}.indexing_job;") || exit 1

log "[2/6] 새 문서를 업로드하세요. 새 indexing Job을 기다립니다. (기준 id=$BASE)"
# 기준 id 뒤에 등록된 첫 Job을 대상으로 삼는다. 시험 중에는 문서 하나만 업로드한다.
echo "준비되면 ENTER를 누른 뒤 웹에서 처리 시간이 충분히 긴 문서를 업로드하세요."
read -r _

JOB=""
for ((i=0; i<UPLOAD_TIMEOUT; i++)); do
  JOB=$(q_try "SELECT id FROM ${DB_SCHEMA}.indexing_job WHERE id > $BASE ORDER BY id LIMIT 1;" || true)
  [ -n "$JOB" ] && break
  sleep "$POLL"
done
[ -n "$JOB" ] || { echo "${UPLOAD_TIMEOUT}초 안에 새 Job을 찾지 못했습니다."; exit 2; }
log "새 Job 발견: id=$JOB"

log "[3/6] Job이 PROCESSING 상태가 될 때까지 대기"
BEFORE=""
for ((i=0; i<PROCESSING_TIMEOUT; i++)); do
  ROW=$(q_try "SELECT status || '|' || COALESCE(worker_id,'') || '|' || attempt_count || '|' || COALESCE(lease_until::text,'') FROM ${DB_SCHEMA}.indexing_job WHERE id=$JOB;" || true)
  if [ -n "$ROW" ]; then
    IFS='|' read -r STATUS WORKER ATTEMPT LEASE <<< "$ROW"
    if [ "$STATUS" = PROCESSING ]; then
      BEFORE="$ROW"
      log "처리 중 확인: status=$STATUS worker=$WORKER attempt=$ATTEMPT lease=$LEASE"
      break
    fi
    if [ "$STATUS" = COMPLETED ] || [ "$STATUS" = FAILED ]; then
      echo "장애 주입 전에 Job이 $STATUS 되었습니다. 더 오래 걸리는 문서로 다시 실행하세요."
      exit 2
    fi
  fi
  sleep "$POLL"
done
[ -n "$BEFORE" ] || { echo "${PROCESSING_TIMEOUT}초 안에 PROCESSING을 관측하지 못했습니다."; exit 2; }

echo
log "[4/6] 현재 OpenSQL Primary에 장애 주입"
kill_current_primary || { echo "Primary 장애 주입에 실패했습니다."; exit 1; }
log "이후 승격 대상/Replica 상태/원복은 제어하지 않습니다. HA/OpenProxy 자동 복구를 관찰합니다."
log "DB 연결 실패를 실제로 관측할 때까지 기다립니다."

OUTAGE_SEEN=0
# 장애 주입 명령의 성공만으로 통과시키지 않고 실제 DB 조회 실패를 기다린다.
for ((i=0; i<OUTAGE_TIMEOUT; i++)); do
  if ! primary_health >/dev/null 2>&1; then
    OUTAGE_SEEN=1
    OUTAGE_AT=$(date '+%Y-%m-%d %H:%M:%S')
    log "DB 장애 관측: $OUTAGE_AT"
    break
  fi
  sleep "$POLL"
done
[ "$OUTAGE_SEEN" = 1 ] || {
  echo "${OUTAGE_TIMEOUT}초 동안 DB 연결 실패를 관측하지 못했습니다. 장애가 실제로 발생했는지 확인하세요."
  exit 2
}

log "[5/6] OpenProxy를 통한 writable Primary 복구 대기"
RECOVERED=""
for ((i=0; i<RECOVERY_TIMEOUT; i++)); do
  H=$(primary_health || true)
  if [ -n "$H" ]; then
    IFS='|' read -r R RO S <<< "$H"
    if [ "$R" = false ] && [ "$RO" = off ]; then
      RECOVERED="$H"
      RECOVERED_AT=$(date '+%Y-%m-%d %H:%M:%S')
      log "DB 쓰기 경로 복구: server=$S, time=$RECOVERED_AT"
      break
    fi
  fi
  sleep "$POLL"
done
[ -n "$RECOVERED" ] || { echo "${RECOVERY_TIMEOUT}초 안에 writable Primary 연결이 복구되지 않았습니다."; exit 1; }

log "[6/6] 기존 Job의 재처리 및 최종 완료 대기"
LAST=""
SEEN_RETRY=0
SEEN_RECLAIM=0
# RETRY_WAIT·재선점 관측은 참고 정보다. 성공 여부는 같은 Job의 COMPLETED로 판정한다.
FINAL=""
for ((i=0; i<COMPLETION_TIMEOUT; i++)); do
  ROW=$(q_try "SELECT status || '|' || COALESCE(worker_id,'') || '|' || attempt_count || '|' || COALESCE(lease_until::text,'') || '|' || COALESCE(phase::text,'') FROM ${DB_SCHEMA}.indexing_job WHERE id=$JOB;" || true)
  if [ -n "$ROW" ] && [ "$ROW" != "$LAST" ]; then
    log "Job: $ROW"
    LAST="$ROW"
  fi
  if [ -n "$ROW" ]; then
    IFS='|' read -r STATUS WORKER2 ATTEMPT2 LEASE2 PHASE2 <<< "$ROW"
    [ "$STATUS" = RETRY_WAIT ] && SEEN_RETRY=1
    if [ "$STATUS" = PROCESSING ] && { [ "$WORKER2" != "$WORKER" ] || [ "${ATTEMPT2:-0}" -gt "${ATTEMPT:-0}" ] 2>/dev/null; }; then
      SEEN_RECLAIM=1
    fi
    if [ "$STATUS" = COMPLETED ]; then FINAL="$ROW"; break; fi
    if [ "$STATUS" = FAILED ]; then echo "Job이 FAILED로 종료되었습니다: $ROW"; exit 1; fi
  fi
  sleep "$POLL"
done
[ -n "$FINAL" ] || { echo "${COMPLETION_TIMEOUT}초 안에 Job이 COMPLETED 되지 않았습니다."; exit 1; }

echo
echo "================ DB 장애 복구 테스트 PASS ================"
echo "Job ID              : $JOB"
echo "장애 전              : $BEFORE"
echo "DB 장애 관측         : $OUTAGE_AT"
echo "DB 쓰기 복구         : $RECOVERED_AT"
echo "최종 상태            : $FINAL"
echo "RETRY_WAIT 관측      : $SEEN_RETRY"
echo "재선점 정황 관측     : $SEEN_RECLAIM"
echo "검증                 : DB 장애/복구 후 저장된 미완료 Job이 최종 COMPLETED"
echo "==========================================================="
