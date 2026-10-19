#!/usr/bin/env python3
"""임베딩 API 장애 시나리오용 스텁.

장애 구간(--outage-seconds) 동안은 --mode 에 맞는 오류를 돌려주고, 그 뒤에는 정상 임베딩을 돌려준다.
구간은 기본으로 기동 시각부터 세고, --outage-from first-request 이면 첫 임베딩 요청을 받은 시각부터 센다.
--fail-requests N 을 지정하면 시간과 무관하게 처음 N개 요청만 실패시키고 이후에는 가짜 벡터를 반환한다.
POST /v1/embeddings 만 처리한다. GET /stats 는 모드, 장애 구간 남은 시간, 응답별 요청 수를 돌려준다.
Worker 의 SPRING_AI_OPENAI_BASE_URL 을 http://127.0.0.1:<port>/v1 로 지정해 쓴다.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import NamedTuple, Optional

# Worker 가 응답마다 검사하는 차원이다. 어긋나면 InvalidEmbeddingException 으로 영구 실패한다. 값은 Worker 가 소유한다.
DIMENSIONS = 1536
MODES = ("5xx", "quota", "429", "timeout", "401")
EMBEDDINGS_PATH = "/v1/embeddings"
STATS_PATH = "/stats"
OUTAGE_STARTS = ("start", "first-request")
# 429 응답의 Retry-After 초. embedding-backoff-recovery.sh 가 같은 값을 기대한다.
RATE_LIMIT_RETRY_AFTER_SECONDS = 30


class Reply(NamedTuple):
    status: Optional[int]  # None 이면 응답하지 않고 연결만 닫는다
    headers: dict
    body: bytes
    hang_seconds: float


class Stats:
    """응답 결과(상태 코드 또는 hang)별 요청 수. watch.py 가 /stats 로 읽는다."""

    def __init__(self):
        self._lock = threading.Lock()
        self._counts = {}

    def record(self, result):
        with self._lock:
            self._counts[str(result)] = self._counts.get(str(result), 0) + 1

    def snapshot(self):
        with self._lock:
            return dict(self._counts)


class OutageWindow:
    """장애 구간 시계. started 가 None 이면 begin() 이 처음 불린 시각부터 센다. 시각은 모두 같은 시계(time.monotonic)다."""

    def __init__(self, seconds, started=None, fail_requests=None):
        self.seconds = seconds
        self._started = started
        self._fail_requests = fail_requests
        self._failed_requests = 0
        self._lock = threading.Lock()

    def fail_request(self, now):
        """횟수 제한 모드에서는 동시 요청이 와도 지정한 횟수만 실패시킨다."""
        with self._lock:
            if self._started is None:
                self._started = now
            if self._fail_requests is not None:
                if self._failed_requests >= self._fail_requests:
                    return False
                self._failed_requests += 1
                return True
            return now - self._started < self.seconds

    def begin(self, now):
        with self._lock:
            if self._started is None:
                self._started = now

    def active(self, now):
        with self._lock:
            return self._started is not None and now - self._started < self.seconds

    def remaining(self, now):
        with self._lock:
            if self._fail_requests is not None:
                return 0.0  # 횟수 제한 모드에는 시간 기반 장애 구간이 없다.
            if self._started is None:
                return self.seconds
            return max(0.0, self.seconds - (now - self._started))


def stats_payload(mode, window, now, counts):
    """GET /stats 응답 본문. window 와 now 는 같은 시계(time.monotonic)를 쓴다."""
    return {"mode": mode, "outageSeconds": window.seconds,
            "outageRemainingSeconds": window.remaining(now), "requests": counts}


def _error(status, err_type, code, message, headers=None):
    body = json.dumps({"error": {"message": message, "type": err_type, "code": code}}).encode()
    return Reply(status, headers or {}, body, 0)


def _vector(index):
    # 입력 순서마다 다른 고정값이다. Worker 는 값이 아니라 차원 수만 검사한다.
    return [(index + 1) / 100.0] * DIMENSIONS


def count_inputs(payload):
    """요청의 input 개수를 돌려준다. input 이 없거나 비어 있으면 ValueError 를 낸다."""
    value = payload.get("input")
    if isinstance(value, str) and value:
        return 1
    if isinstance(value, list) and value:
        return len(value)
    raise ValueError("input 이 필요하다")


def plan_reply(mode, in_outage, input_count, hang_seconds, retry_after=RATE_LIMIT_RETRY_AFTER_SECONDS):
    """요청 한 건에 돌려줄 응답을 정한다. timeout 모드의 장애 구간은 status 가 None 인 Reply 를 돌려준다."""
    if not in_outage:
        data = [{"object": "embedding", "index": i, "embedding": _vector(i)} for i in range(input_count)]
        body = json.dumps({
            "object": "list", "data": data, "model": "text-embedding-3-small",
            "usage": {"prompt_tokens": 0, "total_tokens": 0},
        }).encode()
        return Reply(200, {}, body, 0)
    if mode == "5xx":
        return _error(503, "server_error", "server_error", "The server is overloaded (stub)")
    if mode == "quota":
        return _error(429, "insufficient_quota", "insufficient_quota", "You exceeded your current quota (stub)")
    if mode == "429":
        # Worker 는 Retry-After 를 재시도 시각의 하한으로 읽는다.
        return _error(429, "requests", "rate_limit_exceeded", "Rate limit reached (stub)",
                      {"Retry-After": str(retry_after)})
    if mode == "401":
        return _error(401, "invalid_request_error", "invalid_api_key", "Incorrect API key provided (stub)")
    if mode == "timeout":
        return Reply(None, {}, b"", hang_seconds)
    raise ValueError(f"알 수 없는 모드: {mode}")


def make_handler(mode, window, hang_seconds, stats, retry_after=RATE_LIMIT_RETRY_AFTER_SECONDS):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.split("?")[0] != STATS_PATH:
                return self._send(Reply(404, {}, b'{"error":{"message":"not found"}}', 0))
            payload = stats_payload(mode, window, time.monotonic(), stats.snapshot())
            self._send(Reply(200, {}, json.dumps(payload).encode(), 0))

        def do_POST(self):
            if self.path.split("?")[0] != EMBEDDINGS_PATH:
                return self._send(Reply(404, {}, b'{"error":{"message":"not found"}}', 0))
            length = int(self.headers.get("Content-Length") or 0)
            try:
                count = count_inputs(json.loads(self.rfile.read(length) or b"{}"))
            except ValueError:  # JSONDecodeError 도 ValueError 의 하위 클래스다
                return self._send(Reply(400, {}, b'{"error":{"message":"invalid request","type":"invalid_request_error"}}', 0))
            now = time.monotonic()
            in_outage = window.fail_request(now)
            reply = plan_reply(mode, in_outage, count, hang_seconds, retry_after)
            result = "hang" if reply.status is None else reply.status
            stats.record(result)
            self._log(mode, in_outage, result)
            if reply.status is None:
                # 응답 없이 붙잡고 있다가 닫는다. Worker 의 임베딩 타임아웃보다 길어야 클라이언트가 먼저 타임아웃을 낸다.
                time.sleep(reply.hang_seconds)
                self.close_connection = True
                return
            self._send(reply)

        def _send(self, reply):
            self.send_response(reply.status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(reply.body)))
            for name, value in reply.headers.items():
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(reply.body)

        @staticmethod
        def _log(mode_name, in_outage, result):
            print(f"{time.strftime('%H:%M:%S')} {mode_name} {'outage' if in_outage else 'normal'} {result}", flush=True)

        def log_message(self, *args):
            pass  # 기본 접근 로그를 끈다. 요청마다 _log 가 한 줄을 남긴다.

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8081)
    parser.add_argument("--outage-seconds", type=float, default=90)
    parser.add_argument("--mode", choices=MODES, default="5xx")
    parser.add_argument("--hang-seconds", type=float, default=10)
    parser.add_argument("--outage-from", choices=OUTAGE_STARTS, default="start",
                        help="start: 기동 시각부터, first-request: 첫 임베딩 요청을 받은 시각부터 장애 구간을 센다")
    parser.add_argument("--fail-requests", type=int,
                        help="지정한 요청 수만 실패시킨 뒤 가짜 벡터를 반환한다(장애 시간보다 우선)")
    parser.add_argument("--retry-after-seconds", type=int, default=RATE_LIMIT_RETRY_AFTER_SECONDS,
                        help="429 응답의 Retry-After 초(기본 30)")
    args = parser.parse_args()
    if args.fail_requests is not None and args.fail_requests < 0:
        parser.error("--fail-requests 는 0 이상이어야 합니다.")
    if args.retry_after_seconds < 0:
        parser.error("--retry-after-seconds 는 0 이상이어야 합니다.")
    started = time.monotonic() if args.outage_from == "start" else None
    handler = make_handler(args.mode, OutageWindow(args.outage_seconds, started, args.fail_requests),
                           args.hang_seconds, Stats(), args.retry_after_seconds)
    print(f"stub mode={args.mode} outage={args.outage_seconds}s fail_requests={args.fail_requests} "
          f"from={args.outage_from} port={args.port}", flush=True)
    # Docker 로 띄울 때 포트 매핑이 닿으려면 모든 인터페이스에 바인딩해야 한다.
    ThreadingHTTPServer(("0.0.0.0", args.port), handler).serve_forever()


if __name__ == "__main__":
    main()
