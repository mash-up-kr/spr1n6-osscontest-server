#!/usr/bin/env python3
"""임베딩 장애 시나리오의 진행 상황을 터미널에 계속 그려 준다. DB 는 읽기만 한다.

보여 주는 것: 스텁(모드·장애 구간 남은 시간·응답별 요청 수), breaker 상태, Job 상태별 개수, 최근 Job.
Job 개수와 최근 Job 은 시나리오로 만든 것만이 아니라 DB 의 indexing_job 전체 기준이다.
DB 접속은 Worker 와 같은 DB_URL(jdbc:postgresql://host:port/db), DB_USERNAME, DB_PASSWORD 를 쓴다.
psql 은 postgres:17 컨테이너 하나를 띄워 두고 docker exec 로 실행하므로 로컬 설치가 필요 없다.
Ctrl+C 나 SIGTERM 으로 끝내면 그 컨테이너를 지운다. 강제 종료(SIGKILL)하면 남으며 다음 실행이 먼저 지운다.
"""
import argparse
import json
import os
import signal
import subprocess
import sys
import time
import urllib.request

BREAKER_NAME = "embedding-api"
CONTAINER = "spr1n6-watch-psql"
JOB_STATUSES = ("PENDING", "PROCESSING", "RETRY_WAIT", "COMPLETED", "FAILED")
RECENT_JOB_LIMIT = 6  # 터미널 한 화면에 breaker 줄과 함께 들어가는 개수다.
BAR_WIDTH = 20
STUB_TIMEOUT_SECONDS = 2  # 갱신 주기와 같다. 이보다 길면 스텁이 멈췄을 때 화면도 멈춘다.

# 두 테이블(external_circuit_breaker, indexing_job)의 스키마는 API 서버가 소유한다. 여기서는 읽기만 하며,
# 컬럼이 바뀌어 조회가 깨지면 화면에 DB 조회 실패로 드러난다.
QUERY = f"""
SELECT json_build_object(
  'breaker', (SELECT row_to_json(b) FROM (
      SELECT state, consecutive_failures AS failures, generation, probe_owner,
             CASE WHEN opened_until IS NULL THEN NULL
                  ELSE GREATEST(0, extract(epoch FROM opened_until - clock_timestamp())) END AS opened_remaining
      FROM external_circuit_breaker WHERE name = '{BREAKER_NAME}') b),
  'jobs', (SELECT coalesce(json_object_agg(status, n), '{{}}'::json)
           FROM (SELECT status, count(*) AS n FROM indexing_job GROUP BY status) t),
  'recent', (SELECT coalesce(json_agg(r), '[]'::json) FROM (
      SELECT id, status, attempt_count AS attempts, last_error_code AS error, outcome,
             CASE WHEN next_retry_at IS NULL THEN NULL
                  ELSE GREATEST(0, extract(epoch FROM next_retry_at - clock_timestamp())) END AS retry_in
      FROM indexing_job ORDER BY updated_at DESC, id DESC LIMIT {RECENT_JOB_LIMIT}) r)
)
"""

COLORS = {"CLOSED": "32", "OPEN": "31", "HALF_OPEN": "33"}


def parse_db_url(url):
    if not url.startswith("jdbc:postgresql://"):
        raise ValueError("DB_URL 은 jdbc:postgresql://host:port/db 형식이어야 한다")
    rest = url[len("jdbc:postgresql://"):].split("?")[0]
    hostport, _, db = rest.partition("/")
    host, _, port = hostport.partition(":")
    # psql 이 컨테이너 안에서 돌기 때문에 localhost 는 호스트가 아니라 컨테이너 자신이다.
    if host in ("localhost", "127.0.0.1"):
        host = "host.docker.internal"
    return host, int(port or 5432), db


def bar(remaining, total, width):
    if total <= 0:
        return "░" * width
    filled = round(min(max(remaining / total, 0), 1) * width)
    return "█" * filled + "░" * (width - filled)


def _paint(text, code, color):
    return f"\x1b[{code}m{text}\x1b[0m" if color else text


def _seconds(value):
    return "-" if value is None else f"{int(round(value))}s"


def render(snapshot, color):
    lines = [f"임베딩 장애 watch  {snapshot['time']}   (Ctrl+C 로 종료)", ""]

    stub = snapshot.get("stub")
    if stub is None:
        lines.append(f"스텁     스텁 응답 없음 ({snapshot.get('stub_error')})")
    else:
        requests = "  ".join(f"{code}×{count}" for code, count in sorted(stub["requests"].items())) or "아직 없음"
        remaining = stub["outageRemainingSeconds"]
        phase = f"장애 구간 {bar(remaining, stub['outageSeconds'], BAR_WIDTH)} {_seconds(remaining)} 남음" if remaining > 0 else "장애 구간 끝, 정상 응답 중"
        lines.append(f"스텁     mode={stub['mode']}   {phase}   요청: {requests}")

    if snapshot.get("db_error"):
        lines.append(f"DB 조회 실패: {snapshot['db_error']}")
        return "\n".join(lines)

    breaker = snapshot.get("breaker")
    if breaker is None:
        lines.append(f"breaker  breaker 행 없음 ({BREAKER_NAME})")
    else:
        state = _paint(breaker["state"], COLORS.get(breaker["state"], "0"), color)
        extra = f"   열림 {_seconds(breaker['opened_remaining'])} 남음" if breaker["state"] == "OPEN" else ""
        probe = f"   probe={breaker['probe_owner']}" if breaker.get("probe_owner") else ""
        lines.append(f"breaker  {state}   failures={breaker['failures']}  generation={breaker['generation']}{extra}{probe}")

    jobs = snapshot.get("jobs", {})
    lines.append("Job      " + "  ".join(f"{status} {jobs.get(status, 0)}" for status in JOB_STATUSES))

    recent = snapshot.get("recent", [])
    if recent:
        lines += ["", "최근 Job", f"  {'id':>5}  {'status':<11}  {'시도':>3}  {'오류/결과':<32}  다음 재시도"]
        for job in recent:
            # 재시도 끝에 완료된 Job 은 예전 오류 코드가 남아 있으므로 결과(outcome)를 먼저 보여 준다.
            # 대기 중인 Job 의 outcome(RETRY_SCHEDULED)은 원인이 아니므로 오류 코드를 먼저 보여 준다.
            first, second = ("outcome", "error") if job["status"] == "COMPLETED" else ("error", "outcome")
            note = job.get(first) or job.get(second) or "-"
            retry = _seconds(job["retry_in"]) + " 뒤" if job.get("retry_in") is not None else "-"
            lines.append(f"  {job['id']:>5}  {job['status']:<11}  {job['attempts']:>3}  {note:<32}  {retry}")
    return "\n".join(lines)


def fetch_stub(stub_url):
    try:
        with urllib.request.urlopen(f"{stub_url}/stats", timeout=STUB_TIMEOUT_SECONDS) as response:
            return json.load(response), None
    except Exception as error:  # 스텁이 꺼져 있어도 화면은 계속 그린다
        return None, str(error)


def start_psql_container():
    # 강제 종료로 남은 이전 컨테이너가 있으면 이름이 겹치므로 먼저 지운다.
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    subprocess.run(["docker", "run", "-d", "--name", CONTAINER, "--add-host=host.docker.internal:host-gateway",
                    "postgres:17", "sleep", "infinity"], check=True, capture_output=True)


def stop_psql_container():
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)


def query_db(host, port, db, user, password):
    result = subprocess.run(
        ["docker", "exec", "-e", f"PGPASSWORD={password}", CONTAINER, "psql", "-h", host, "-p", str(port),
         "-U", user, "-d", db, "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", QUERY],
        capture_output=True, text=True)
    if result.returncode != 0:
        return None, result.stderr.strip() or "psql 실패"
    return json.loads(result.stdout), None


def _interrupt(*_):
    raise KeyboardInterrupt


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stub-url", default=os.environ.get("STUB_URL", "http://127.0.0.1:8081"))
    parser.add_argument("--interval", type=float, default=2)
    parser.add_argument("--once", action="store_true", help="한 번만 그리고 끝낸다")
    parser.add_argument("--no-color", action="store_true")
    args = parser.parse_args()

    try:
        host, port, db = parse_db_url(os.environ["DB_URL"])
        user, password = os.environ["DB_USERNAME"], os.environ["DB_PASSWORD"]
    except KeyError as missing:
        sys.exit(f"오류: 환경변수 {missing} 가 필요하다 (DB_URL, DB_USERNAME, DB_PASSWORD)")
    except ValueError as error:
        sys.exit(f"오류: {error}")

    # 터미널이 닫히거나 kill 로 끝나도 psql 컨테이너를 지우도록 SIGTERM 을 Ctrl+C 와 같은 경로로 보낸다.
    signal.signal(signal.SIGTERM, _interrupt)
    color = sys.stdout.isatty() and not args.no_color and "NO_COLOR" not in os.environ
    start_psql_container()
    try:
        while True:
            stub, stub_error = fetch_stub(args.stub_url)
            data, db_error = query_db(host, port, db, user, password)
            snapshot = {"time": time.strftime("%H:%M:%S"), "stub": stub, "stub_error": stub_error, "db_error": db_error,
                        "breaker": (data or {}).get("breaker"), "jobs": (data or {}).get("jobs", {}),
                        "recent": (data or {}).get("recent", [])}
            frame = render(snapshot, color)
            print(("\x1b[H\x1b[2J" if color and not args.once else "") + frame, flush=True)
            if args.once:
                break
            time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        stop_psql_container()


if __name__ == "__main__":
    main()
