#!/usr/bin/env bash
# 임베딩 API 429·5xx 백오프 복구 시나리오. 실행 방법은 같은 폴더의 README.md 8장에 있다.
# 스텁·임시 Worker로 첫 요청만 429·5xx로 실패시키고, 재시도 1회 후 가짜 벡터로 COMPLETED가 되는지 확인한다.
# DB는 읽기만 한다. 옵션은 --help 로 본다. 같은 폴더의 embedding-stub.py 를 스텁 컨테이너에 실어 보낸다.
# OpenProxy SSH 터널(localhost:15432)을 미리 열어 두고, 비밀번호는 ~/.pgpass 또는 PGPASSWORD 로 준비한다.
set -euo pipefail
command -v python3 >/dev/null 2>&1 || { echo 'python3가 필요합니다.' >&2; exit 1; }
# Homebrew 의 libpq 는 PATH 에 연결되지 않으므로 psql 이 없으면 알려진 위치에서 찾아 PATH 에 더한다.
if ! command -v psql >/dev/null 2>&1; then
  for dir in /opt/homebrew/opt/libpq/bin /usr/local/opt/libpq/bin /opt/homebrew/opt/postgresql@*/bin /usr/local/opt/postgresql@*/bin; do
    if [ -x "$dir/psql" ]; then export PATH="$dir:$PATH"; break; fi
  done
fi
export EMBEDDING_OUTAGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 - "$@" <<'PYTHON'
"""임베딩 API 429·5xx 장애에서 Worker 의 백오프 재시도와 최종 완료를 확인한다.

기존 Worker 컨테이너 두 대를 시험 동안 멈추고, 같은 이미지·환경변수로 임시 Worker 1개와 임베딩 스텁
(embedding-stub.py)을 docker 로 띄운다. 스텁이 429 와 5xx 를 차례로 일으키고, 사람이 웹에서 올린 문서의
Job 을 읽어 RETRY_WAIT 간격·오류 코드·breaker 상태·최종 COMPLETED 를 판정한다. DB 는 읽기만 한다.
429는 10초 후, 5xx는 10초 기준 ±20% 지터(8~12초) 후 한 번만 재시도한다. 이후에는 가짜 벡터를 받는다.
재시도 설정은 임시 Worker에만 적용하며, 큐 폴링은 1초, 최대 시도는 최초 포함 2회다.
서버의 compose 파일과 기존 Worker 설정은 바꾸지 않는다. 끝나면 임시 컨테이너를 지우고 멈췄던 Worker 를 다시 시작한다.

전제: 이 스크립트를 돌리는 곳에서 docker ps 로 기존 Worker 컨테이너(기본 aidocs-dev-worker-1, aidocs-dev-worker-2)가
보이고, OpenProxy SSH 터널(기본 localhost:15432)이 열려 있다. 비밀번호는 ~/.pgpass 또는 PGPASSWORD 를 쓴다.
실행 방법은 같은 폴더의 README.md 8장에 있다.

종료 코드: 0=모두 PASS, 1=FAIL·환경 오류, 2=관측 부족으로 판정 불가, 130=중단.
"""
import argparse
import base64
import csv
import io
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

BREAKER_NAME = "embedding-api"
JITTER_LOW, JITTER_HIGH = 0.8, 1.2  # RetryPolicy 의 ±20% 지터
MODES = ("429", "5xx")
STUB_RETRY_AFTER_SECONDS = 10
# 8초 × 지터 최대 1.2 = 9.6초이므로 429는 Retry-After에 의해 항상 10초로 예약된다.
RATE_LIMIT_BASE_SECONDS = 8
SERVER_ERROR_BASE_SECONDS = 10
STUB_PATH = Path(os.environ.get("EMBEDDING_OUTAGE_DIR") or Path(__file__).resolve().parent) / "embedding-stub.py"
WORKER_NAME = "embedding-test-worker"
STUB_NAME = "embedding-test-stub"
STUB_PORT = 8081  # 컨테이너 안의 포트. published port 는 쓰지 않는다.
DEFAULT_WORKER1 = "aidocs-dev-worker-1"
DEFAULT_WORKER2 = "aidocs-dev-worker-2"
DEFAULT_STUB_IMAGE = "python:3.12-slim"
BUILTIN_NETWORKS = ("bridge", "host", "none")
WORKER_READY_LOG = "Started WorkerApplicationKt"
# 호스트 파일을 마운트하지 않으려고 스텁 소스를 base64 인자로 넘겨 python -c 로 실행한다.
STUB_BOOTSTRAP = ("import base64,sys;exec(compile(base64.b64decode(sys.argv.pop(1)),"
                  "'embedding-stub.py','exec'),{'__name__':'__main__'})")
STATS_PROBE = ("import urllib.request;print(urllib.request.urlopen("
               f"'http://127.0.0.1:{STUB_PORT}/stats',timeout=2).read().decode())")


class StopTest(Exception):
    def __init__(self, message, code=1):
        super().__init__(message)
        self.code = code


def say(text):
    print(text, flush=True)


def backoff_bounds(base_seconds, attempt_count, retry_after=None):
    """RetryPolicy.decideFailure 의 대기 시간 범위(초). Retry-After 가 있으면 둘 다 그 값 이상이다."""
    factor = max(attempt_count, 1)
    low = base_seconds * factor * JITTER_LOW
    high = base_seconds * factor * JITTER_HIGH
    if retry_after is not None:
        low = max(low, retry_after)
        high = max(high, retry_after)
    return low, high


def base_delay_for(mode):
    return RATE_LIMIT_BASE_SECONDS if mode == "429" else SERVER_ERROR_BASE_SECONDS


@dataclass(frozen=True)
class RetryObservation:
    attempt_count: int
    delay_seconds: float  # next_retry_at - 처음 관측한 시점의 DB 시각. 실제 대기보다 폴링 지연만큼 작다.
    error_code: str


@dataclass(frozen=True)
class Check:
    name: str
    ok: bool
    detail: str
    inconclusive: bool = False


def evaluate(mode, base_seconds, retry_after, retries, final, breaker_states, tolerance):
    """관측 결과를 판정 항목으로 바꾼다. 5xx 에서는 breaker 전이를 판정하지 않는다(문서 한 건으로는 열리지 않을 수 있다)."""
    checks = []
    attempts = [r.attempt_count for r in retries]
    enough = len(retries) == 1
    checks.append(Check("재시도 1회 관측", enough, f"RETRY_WAIT 관측 {len(retries)}회", inconclusive=not retries))
    checks.append(Check("첫 시도 실패 후 재시도", attempts == [1] if retries else True, f"attempt_count={attempts}"))
    effective_retry_after = retry_after if mode == "429" else None
    for r in retries:
        low, high = backoff_bounds(base_seconds, r.attempt_count, effective_retry_after)
        ok = low - tolerance <= r.delay_seconds <= high + tolerance
        checks.append(Check(f"백오프 간격 (attempt {r.attempt_count})", ok,
                            f"대기 {r.delay_seconds:.1f}초, 허용 {low:.1f}~{high:.1f}초(±{tolerance:.1f})"))
    if mode == "429":
        wrong = sorted({str(r.error_code) for r in retries if r.error_code != "RATE_LIMITED"})
        checks.append(Check("오류 코드 RATE_LIMITED", not wrong, f"다른 코드: {wrong}" if wrong else "모두 RATE_LIMITED"))
        checks.append(Check("breaker CLOSED 유지", all(s == "CLOSED" for s in breaker_states),
                            f"관측한 상태={breaker_states}"))
    else:
        missing = [r.attempt_count for r in retries if not r.error_code]
        checks.append(Check("오류 코드 기록", not missing,
                            f"오류 코드 없는 attempt={missing}" if missing else "모두 기록됨"))
    done = final is not None and final["status"] == "COMPLETED"
    checks.append(Check("같은 Job이 COMPLETED", done, f"최종 상태={final['status'] if final else '관측 없음(시간 초과)'}"))
    if done:
        checks.append(Check("outcome INDEXED", final["outcome"] == "INDEXED", f"outcome={final['outcome']}"))
        checks.append(Check("총 시도 2회(재시도 1회)", final["attempt_count"] == 2,
                            f"attempt_count={final['attempt_count']}"))
    return checks


def exit_code(checks):
    if any(not c.ok and not c.inconclusive for c in checks):
        return 1
    if any(not c.ok for c in checks):
        return 2
    return 0


# ---- DB (읽기 전용) ---------------------------------------------------------------------------------------------
# db-job-recovery.sh·worker-lease-recovery.sh 와 같은 방식이다. 커넥션 풀 없이 조회마다 새 psql 세션을 열고,
# OpenProxy 가 쓰기 대상을 연결마다 정하므로 첫 문장으로 SET SERVER ROLE 을 보낸다.
NULL_TOKEN = "__NULL_" + uuid.uuid4().hex + "__"
ROW_COLUMN = "__row__"
ROW_MARKER = "__ROW__"

PRIMARY_SQL = ("SELECT pg_is_in_recovery()::text AS standby, current_setting('transaction_read_only') AS read_only, "
               "host(inet_server_addr()) AS server")
UNFINISHED_SQL = ("SELECT COUNT(*)::text AS count FROM :\"schema\".indexing_job "
                  "WHERE status IN ('PENDING','PROCESSING','RETRY_WAIT')")
BREAKER_SQL = ("SELECT state, consecutive_failures::text AS failures FROM :\"schema\".external_circuit_breaker "
               "WHERE name = :'breaker'")
BASELINE_SQL = "SELECT COALESCE(MAX(id),0)::text AS baseline FROM :\"schema\".indexing_job"
NEW_JOBS_SQL = "SELECT id::text AS id FROM :\"schema\".indexing_job WHERE id > :'baseline'::bigint ORDER BY id"
JOB_SQL = ("SELECT id::text AS id, status, attempt_count::text AS attempt_count, outcome, last_error_code, "
           "EXTRACT(EPOCH FROM next_retry_at)::float8::text AS next_retry_epoch, "
           "EXTRACT(EPOCH FROM clock_timestamp())::float8::text AS db_now_epoch "
           "FROM :\"schema\".indexing_job WHERE id = :'job_id'::bigint")


class DbError(Exception):
    pass


def run_psql(command, script, timeout=12):
    env = dict(os.environ)
    env.setdefault("PGCONNECT_TIMEOUT", "5")
    env["PGOPTIONS"] = (env.get("PGOPTIONS", "") + " -c statement_timeout=5000 -c lock_timeout=2000").strip()
    try:
        proc = subprocess.run(command, input=script, text=True, capture_output=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired:
        raise DbError("psql 시간 초과")
    except FileNotFoundError:
        raise DbError("psql 명령이 필요합니다.")
    if proc.returncode:
        raise DbError(f"psql 실패: {proc.stderr.strip() or proc.stdout.strip()}")
    return proc.stdout


def parse_rows(raw):
    """psql --csv 출력에서 마커가 붙은 SELECT 행만 읽는다. NULL 은 None 이고, 값은 모두 문자열이다."""
    header = None
    rows = []
    try:
        for fields in csv.reader(io.StringIO(raw, newline=""), strict=True):
            if not fields:
                continue
            if header is None:
                if fields[0] == ROW_COLUMN:
                    header = fields
                continue
            if fields[0] != ROW_MARKER:
                continue
            if len(fields) != len(header):
                raise DbError(f"열 개수가 다릅니다: {fields}")
            rows.append({name: (None if value == NULL_TOKEN else value)
                         for name, value in zip(header[1:], fields[1:])})
    except csv.Error as error:
        raise DbError(f"psql 출력 해석 실패: {error}") from error
    if header is None:
        raise DbError(f"SELECT 결과 헤더가 없습니다: {raw[:200]!r}")
    return rows


class Db:
    def __init__(self, env=None, runner=run_psql):
        env = os.environ if env is None else env
        self.schema = env.get("DB_SCHEMA", "public")
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", self.schema):
            raise ValueError("DB_SCHEMA 는 영문/숫자/밑줄로 지정하세요.")
        self.role = env.get("DB_SERVER_ROLE", "default")  # 빈 값이면 SET SERVER ROLE 을 보내지 않는다(일반 PostgreSQL)
        self._base = ["psql", "-X", "-w", "-q", "--csv", "-P", "null=" + NULL_TOKEN, "-v", "ON_ERROR_STOP=1",
                      "-h", env.get("DB_HOST", "localhost"), "-p", env.get("DB_PORT", "15432"),
                      "-U", env.get("DB_USER", "aidocs"), "-d", env.get("DB_NAME", "aidocs"),
                      "-v", "schema=" + self.schema]
        self._runner = runner

    def rows(self, sql, **params):
        command = list(self._base)
        for key, value in params.items():
            command += ["-v", f"{key}={value}"]
        statement = sql.strip().rstrip(";")
        script = f"SELECT '{ROW_MARKER}' AS {ROW_COLUMN}, q.* FROM (\n{statement}\n) AS q;\n"
        if self.role:
            script = f"SET SERVER ROLE TO '{self.role}';\n" + script
        return parse_rows(self._runner(command, script))

    def one(self, sql, **params):
        rows = self.rows(sql, **params)
        if len(rows) > 1:
            raise DbError("단일 행 조회에서 여러 행이 반환됐습니다.")
        return rows[0] if rows else None


def preflight(db):
    """시작 전 점검. 통과하면 None, 아니면 사유를 돌려준다."""
    health = db.one(PRIMARY_SQL)
    if health is None or health["standby"] != "false" or health["read_only"] != "off":
        return "쓰기 가능한 Primary에 연결되지 않았습니다. 터널·OpenProxy 상태를 확인하세요."
    unfinished = int(db.one(UNFINISHED_SQL)["count"])
    if unfinished:
        return f"미종결 Job {unfinished}건이 있습니다. 모두 끝난 뒤 실행하세요."
    breaker = db.one(BREAKER_SQL, breaker=BREAKER_NAME)
    if breaker is None:
        return f"breaker 행({BREAKER_NAME})이 없습니다. 마이그레이션이 적용된 DB인지 확인하세요."
    if breaker["state"] != "CLOSED" or int(breaker["failures"]) != 0:
        return f"breaker가 CLOSED(failures=0)가 아닙니다: {breaker['state']}/{breaker['failures']}"
    return None


# ---- 관측 ---------------------------------------------------------------------------------------------------------
@dataclass
class Observation:
    retries: list
    breaker_states: list
    final: dict
    timed_out: bool


def observe(db, job_id, timeout, poll, clock=time.monotonic, sleep=time.sleep, say=say):
    """Job 이 COMPLETED/FAILED 가 될 때까지 폴링하며 RETRY_WAIT 와 breaker 상태를 모은다."""
    retries, states, seen = [], [], set()
    final = None
    last_signature = None
    end = clock() + timeout
    while clock() < end:
        try:
            row = db.one(JOB_SQL, job_id=job_id)
            breaker = db.one(BREAKER_SQL, breaker=BREAKER_NAME)
        except DbError as error:
            say(f"  DB 조회 실패, 다시 시도합니다: {error}")
            sleep(poll)
            continue
        if row is None:
            raise StopTest(f"대상 Job이 사라졌습니다: id={job_id}", 2)
        attempt = int(row["attempt_count"])
        breaker_state = breaker["state"] if breaker else "?"
        if breaker and (not states or states[-1] != breaker_state):
            states.append(breaker_state)
        wait = None
        if row["status"] == "RETRY_WAIT" and row["next_retry_epoch"] is not None:
            wait = float(row["next_retry_epoch"]) - float(row["db_now_epoch"])
            key = (attempt, row["next_retry_epoch"])
            if key not in seen:
                seen.add(key)
                retries.append(RetryObservation(attempt, wait, row["last_error_code"]))
        signature = (row["status"], attempt, breaker_state)
        if signature != last_signature:
            note = f" | 다음 재시도까지 {wait:.1f}초" if wait is not None else ""
            say(f"  Job {job_id} | {row['status']} | attempt={attempt} | error={row['last_error_code'] or '-'}"
                f" | breaker={breaker_state}{note}")
            last_signature = signature
        if row["status"] in ("COMPLETED", "FAILED"):
            final = {"id": int(row["id"]), "status": row["status"], "attempt_count": attempt,
                     "outcome": row["outcome"]}
            break
        sleep(poll)
    return Observation(retries, states, final, final is None)


def wait_for_new_job(db, baseline, timeout, poll, upload, clock=time.monotonic, sleep=time.sleep):
    end = clock() + timeout
    while clock() < end:
        check_upload(upload)
        rows = db.rows(NEW_JOBS_SQL, baseline=baseline)
        if len(rows) > 1:
            raise StopTest("새 Job이 여러 개 등록됐습니다. 대상을 임의로 고르지 않습니다.", 2)
        if rows:
            return int(rows[0]["id"])
        sleep(poll)
    raise StopTest(f"{timeout:.0f}초 안에 새 Job을 찾지 못했습니다.", 2)


# ---- 프로세스 ------------------------------------------------------------------------------------------------------
def docker(*args, input=None, timeout=60, with_stderr=False):
    """docker 명령을 실행해 표준 출력을 돌려준다. 실패하면 StopTest 다. 테스트는 이 함수를 가짜로 바꾼다."""
    try:
        proc = subprocess.run(["docker", *args], input=input, text=True, capture_output=True, timeout=timeout)
    except FileNotFoundError:
        raise StopTest("docker 명령이 필요합니다.", 1)
    except subprocess.TimeoutExpired:
        raise StopTest(f"docker {args[0]} 시간 초과({timeout}초)", 1)
    if proc.returncode:
        raise StopTest(f"docker {' '.join(args[:2])} 실패: {proc.stderr.strip() or proc.stdout.strip()}", 1)
    return proc.stdout + proc.stderr if with_stderr else proc.stdout


def docker_quiet(runner, *args):
    """정리용 docker 호출. 실패해도 무시한다(없는 컨테이너를 지우려는 경우 등)."""
    try:
        runner(*args)
    except StopTest:
        pass


def build_env_file(env_list, overrides):
    """docker --env-file 내용. env_list 는 'KEY=VALUE' 목록이고, overrides 가 같은 키를 덮어쓴다(중복 줄 없음)."""
    merged = {}
    for item in env_list:
        key, sep, value = item.partition("=")
        if sep and key:
            merged[key] = value
    merged.update(overrides)
    return "".join(f"{key}={value}\n" for key, value in merged.items())


def pick_network(info):
    """docker inspect 의 컨테이너 한 건에서 bridge·host·none 이 아닌 네트워크 이름을 고른다."""
    names = sorted(name for name in (info.get("NetworkSettings", {}).get("Networks") or {})
                   if name not in BUILTIN_NETWORKS)
    if not names:
        raise StopTest("사용자 정의 네트워크가 필요합니다. Worker 컨테이너가 compose 네트워크 같은 "
                       "사용자 정의 네트워크에 연결돼 있어야 임시 컨테이너가 이름으로 서로를 찾습니다.", 1)
    return names[0]


def stub_command(network, image, mode, script_path=STUB_PATH):
    """스텁 컨테이너를 만드는 docker run 인자(docker 는 제외)."""
    encoded = base64.b64encode(Path(script_path).read_bytes()).decode()
    return ["run", "-d", "--name", STUB_NAME, "--network", network, image, "python", "-c", STUB_BOOTSTRAP, encoded,
            "--port", str(STUB_PORT), "--mode", mode, "--fail-requests", "1",
            "--retry-after-seconds", str(STUB_RETRY_AFTER_SECONDS)]


def ensure_image(runner, image):
    """스텁 이미지가 없으면 받아 둔다. Worker 를 멈추기 전에 하므로 느린 pull 이 시험을 깨뜨리지 않는다."""
    try:
        runner("image", "inspect", image)
        return
    except StopTest:
        pass
    say(f"스텁 이미지({image})가 없어 받습니다. 처음 한 번만 걸립니다.")
    runner("pull", image, timeout=600)


def inspect_workers(runner, args):
    """기존 Worker 두 대를 읽어 (이미지, 환경변수, 네트워크)를 돌려준다. 미기동 또는 이미지 불일치면 StopTest."""
    infos = []
    for name in (args.worker1, args.worker2):
        try:
            info = json.loads(runner("inspect", "--format", "{{json .}}", name))
        except json.JSONDecodeError:
            raise StopTest(f"docker inspect 응답 해석 실패: {name}", 1)
        if not info.get("State", {}).get("Running"):
            raise StopTest(f"Worker {name} 가 실행 중이 아닙니다. 실행 중인 두 Worker 가 필요합니다.", 1)
        infos.append(info)
    # 태그(latest 등)는 컨테이너를 만든 뒤 pull 로 다른 이미지를 가리킬 수 있다. 실제로 쓰는 이미지 ID 로 맞춘다.
    images = [info.get("Image") or info["Config"]["Image"] for info in infos]
    if images[0] != images[1]:
        tags = [info["Config"]["Image"] for info in infos]
        raise StopTest(f"두 Worker 의 이미지가 다릅니다: {tags[0]}({images[0][:19]}) / {tags[1]}({images[1][:19]})", 1)
    return images[0], infos[0]["Config"].get("Env") or [], pick_network(infos[0])


def stub_stats(runner):
    """스텁 컨테이너 안에서 /stats 를 읽는다. 스텁이 아직 준비되지 않았으면 StopTest."""
    try:
        return json.loads(runner("exec", STUB_NAME, "python", "-c", STATS_PROBE, timeout=15))
    except json.JSONDecodeError:
        raise StopTest("스텁 /stats 응답 해석 실패", 1)


def total_requests(stats):
    return sum(int(v) for v in stats.get("requests", {}).values())


def wait_stub_ready(runner, timeout=30.0, clock=time.monotonic, sleep=time.sleep):
    end = clock() + timeout
    while True:
        try:
            return stub_stats(runner)
        except StopTest as error:
            if clock() >= end:
                raise StopTest(f"스텁이 {timeout:.0f}초 안에 준비되지 않았습니다: {error}", 1)
            sleep(1)


def wait_worker_ready(runner, timeout=120.0, clock=time.monotonic, sleep=time.sleep):
    end = clock() + timeout
    while clock() < end:
        logs = runner("logs", WORKER_NAME, with_stderr=True)
        if WORKER_READY_LOG in logs:
            return
        running = runner("inspect", "--format", "{{.State.Running}}", WORKER_NAME).strip()
        if running != "true":
            tail = "\n".join(logs.splitlines()[-20:])
            raise StopTest(f"임시 Worker 가 시작 중에 종료됐습니다. 마지막 로그:\n{tail}", 1)
        sleep(2)
    raise StopTest(f"임시 Worker 가 {timeout:.0f}초 안에 준비되지 않았습니다.", 1)


def follow_logs(name):
    """컨테이너 로그를 이 터미널에 이어서 보여 준다."""
    return subprocess.Popen(["docker", "logs", "-f", name])


def stop_follow(proc):
    if proc is None or proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def start_upload(command):
    if not command:
        return None
    return subprocess.Popen(["bash", "-lc", command], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, start_new_session=True)


def check_upload(process):
    if process is not None:
        code = process.poll()
        if code not in (None, 0):
            raise StopTest(f"업로드 명령이 종료 코드 {code}로 실패했습니다.")


def stop_upload(process):
    if process is None or process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except OSError:
        pass


# ---- 실행 -------------------------------------------------------------------------------------------------------
def tolerance_for(poll_interval):
    """처음 관측이 실제 실패보다 늦은 만큼(폴링 간격 + 조회 시간)을 허용한다."""
    return poll_interval + 2.0


def print_report(mode, checks):
    say(f"\n---- {mode} 결과 ----")
    for check in checks:
        mark = "PASS" if check.ok else ("판정 불가" if check.inconclusive else "FAIL")
        say(f"  [{mark}] {check.name} — {check.detail}")


def worst_code(codes):
    if 1 in codes:
        return 1
    if 2 in codes:
        return 2
    return 0


def run_mode(db, args, mode, runner=docker, follow=follow_logs, sleep=time.sleep):
    say(f"\n==== {mode} 시험 ====")
    network, stub_image = args.network, args.stub_image
    logs = upload = None
    try:
        docker_quiet(runner, "rm", "-f", STUB_NAME)
        runner(*stub_command(network, stub_image, mode))
        before = total_requests(wait_stub_ready(runner, sleep=sleep))
        logs = follow(STUB_NAME)
        baseline = int(db.one(BASELINE_SQL)["baseline"])
        say(f"  스텁 준비됨({STUB_NAME}:{STUB_PORT}, 첫 요청만 실패, 이후 가짜 벡터 반환).")
        if args.upload_command:
            upload = start_upload(args.upload_command)
            say("  업로드 명령을 실행했습니다.")
        else:
            say("\n>>> 지금 웹에서 테스트 문서 하나를 업로드하세요. 시험 중에는 다른 문서를 올리지 마세요. <<<\n")
        job_id = wait_for_new_job(db, baseline, args.upload_timeout, args.poll_interval, upload)
        say(f"  새 Job 발견: id={job_id}")
        obs = observe(db, job_id, args.completion_timeout, args.poll_interval)
        stats = stub_stats(runner)
        after = total_requests(stats)
    finally:
        stop_upload(upload)
        stop_follow(logs)
        docker_quiet(runner, "rm", "-f", STUB_NAME)
    if after <= before:
        raise StopTest("스텁이 요청을 받지 못했습니다. 임시 Worker 가 스텁을 보지 못한 것 같습니다.", 2)
    retry_after = STUB_RETRY_AFTER_SECONDS if mode == "429" else None
    checks = evaluate(mode, base_delay_for(mode), retry_after, obs.retries, obs.final, obs.breaker_states,
                      tolerance_for(args.poll_interval))
    counts = stats.get("requests", {})
    errors = sum(int(count) for status, count in counts.items() if status != "200")
    expected_error = "429" if mode == "429" else "503"
    checks.append(Check("스텁 오류 응답 정확히 1회", errors == 1 and counts.get(expected_error, 0) == 1,
                        f"응답별 요청 수={counts}"))
    checks.append(Check("가짜 벡터 정상 응답", counts.get("200", 0) >= 1, f"200 응답={counts.get('200', 0)}회"))
    print_report(mode, checks)
    return exit_code(checks)


def parse_args(argv, env=None):
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(prog="embedding-backoff-recovery.sh", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--modes", default="429,5xx", help="시험할 모드를 쉼표로 구분(기본 429,5xx)")
    parser.add_argument("--worker1", default=env.get("WORKER1_CONTAINER", DEFAULT_WORKER1),
                        help=f"기존 Worker 1 컨테이너 이름(기본 {DEFAULT_WORKER1})")
    parser.add_argument("--worker2", default=env.get("WORKER2_CONTAINER", DEFAULT_WORKER2),
                        help=f"기존 Worker 2 컨테이너 이름(기본 {DEFAULT_WORKER2})")
    parser.add_argument("--stub-image", default=env.get("STUB_IMAGE", DEFAULT_STUB_IMAGE),
                        help=f"스텁을 실행할 Python 이미지(기본 {DEFAULT_STUB_IMAGE})")
    parser.add_argument("--poll-interval", type=float, default=1.0, help="DB 폴링 간격(초, 기본 1)")
    parser.add_argument("--upload-timeout", type=float, default=300.0)
    parser.add_argument("--completion-timeout", type=float, default=600.0)
    parser.add_argument("--upload-command", help="문서 1건을 올리는 명령. 생략하면 웹 업로드를 기다린다")
    parser.add_argument("--check-only", action="store_true", help="시작 전 점검만 하고 끝낸다")
    args = parser.parse_args(argv)
    args.modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    if not args.modes or any(m not in MODES for m in args.modes):
        parser.error(f"--modes 는 {', '.join(MODES)} 중에서 고르세요.")
    if args.worker1 == args.worker2:
        parser.error("--worker1 과 --worker2 는 서로 다른 컨테이너여야 합니다.")
    if min(args.poll_interval, args.upload_timeout, args.completion_timeout) <= 0:
        parser.error("간격과 timeout 은 양수여야 합니다.")
    return args


def restore_workers(runner, stopped):
    """멈췄던 기존 Worker 만 다시 시작한다. 하나라도 실패하면 False."""
    ok = True
    for name in stopped:
        try:
            runner("start", name, timeout=60)
            say(f"  Worker 다시 시작: {name}")
        except StopTest as error:
            ok = False
            say(f"  수동 복구 필요: docker start {name} ({error})")
    return ok


class Cleanup:
    """시험 중 만든 것과 멈춘 것을 기억해 finally 에서 되돌린다."""

    def __init__(self):
        self.needed = False
        self.stopped = []
        self.env_file = None


def run_all(args, cleanup, runner, db, follow, sleep):
    db = db or Db()
    reason = preflight(db)
    if reason:
        say(f"시작할 수 없습니다: {reason}")
        return 1
    say("시작 전 점검 통과: 쓰기 가능한 Primary, 미종결 Job 없음, breaker CLOSED")
    image, env_list, args.network = inspect_workers(runner, args)
    say(f"Worker 점검 통과: 이미지={image}, 네트워크={args.network}")
    ensure_image(runner, args.stub_image)
    if args.check_only:
        return 0
    say("이 큐를 소비하는 Worker 는 지정한 두 대뿐이어야 하고, 시험 동안 기존 Worker 가 멈춥니다. 끝나면 다시 시작합니다.")
    say("이 시험 동안 같은 Worker·DB를 쓰는 다른 시험이나 문서 업로드를 하지 마세요.")
    cleanup.needed = True
    docker_quiet(runner, "rm", "-f", WORKER_NAME, STUB_NAME)
    for name in (args.worker1, args.worker2):
        cleanup.stopped.append(name)  # 응답이 유실돼도 finally 에서 다시 시작할 수 있게 먼저 기록한다
        runner("stop", "--time", "30", name, timeout=60)
    say(f"기존 Worker 정지: {', '.join(cleanup.stopped)}")
    fd, cleanup.env_file = tempfile.mkstemp(prefix="embedding-test-worker-", suffix=".env")
    os.chmod(cleanup.env_file, 0o600)
    os.close(fd)
    codes = []
    for index, mode in enumerate(args.modes):
        if index:
            reason = preflight(db)
            if reason:
                say(f"다음 모드를 시작할 수 없습니다: {reason}")
                return 1
        # 모드마다 재생성해 429의 Retry-After 하한과 5xx의 10초 기준을 각각 적용한다.
        with open(cleanup.env_file, "w") as handle:
            handle.write(build_env_file(env_list, {
                "SPRING_AI_OPENAI_BASE_URL": f"http://{STUB_NAME}:{STUB_PORT}/v1",
                "INDEXING_WORKER_ID": WORKER_NAME,
                "INDEXING_RETRY_BASE_DELAY": f"PT{base_delay_for(mode)}S",
                "INDEXING_MAX_ATTEMPTS": "2",
                "INDEXING_QUEUE_POLL_INTERVAL": "PT1S"}))
        runner("run", "-d", "--name", WORKER_NAME, "--network", args.network, "--env-file", cleanup.env_file, image)
        say(f"임시 Worker 시작({WORKER_NAME}, {mode}). 준비를 기다립니다(최대 120초).")
        wait_worker_ready(runner, sleep=sleep)
        codes.append(run_mode(db, args, mode, runner, follow, sleep))
        runner("rm", "-f", WORKER_NAME)
    code = worst_code(codes)
    say(f"\n종합: {'PASS' if code == 0 else '판정 불가' if code == 2 else 'FAIL'} (모드별 종료 코드 {codes})")
    return code


def restore(runner, cleanup):
    """임시 컨테이너와 env 파일을 지우고 멈췄던 기존 Worker 만 다시 시작한다. 복구에 실패하면 False."""
    docker_quiet(runner, "rm", "-f", WORKER_NAME, STUB_NAME)
    if cleanup.env_file:
        try:
            os.unlink(cleanup.env_file)
        except OSError:
            pass
    ok = True
    for name in cleanup.stopped:
        try:
            runner("start", name, timeout=60)
            say(f"기존 Worker 다시 시작: {name}")
        except StopTest as error:
            ok = False
            say(f"수동 복구 필요: docker start {name} ({error})")
    return ok


def main(argv=None, runner=docker, db=None, follow=follow_logs, sleep=time.sleep):
    args = parse_args(sys.argv[1:] if argv is None else argv)

    def interrupted(signum, frame):
        raise StopTest(f"사용자 중단(signal={signum})", 130)

    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    cleanup = Cleanup()
    code = 1
    restored = True
    try:
        code = run_all(args, cleanup, runner, db, follow, sleep)
    except StopTest as error:
        say(f"\n종료: {error}")
        code = error.code
    except DbError as error:
        say(f"\nDB 오류: {error}")
        code = 1
    finally:
        if cleanup.needed:
            restored = restore(runner, cleanup)
    return code if restored else max(code, 1)


if __name__ == "__main__":
    sys.exit(main())
PYTHON
