"""노드에 SSH 로 bash 스크립트를 보내 실행한다.

스크립트는 표준입력으로 보내고 main() 함수로 감싸 끝까지 읽은 뒤에 실행한다. 그래야 스크립트 안의 명령이
표준입력을 읽어 나머지 스크립트를 삼키는 일이 없다.
"""
from __future__ import annotations

import queue
import shlex
import subprocess
import threading
import time
from dataclasses import dataclass
from typing import List, Mapping, Optional

from .clock import fmt_time
from .config import Config

# 재부팅 중인 노드를 오래 기다리지 않게 접속 제한을 짧게 둔다. ServerAlive 5초 × 3번이면 죽은 연결을
# 15초 안에 버린다. 제어 소켓은 macOS 의 소켓 경로 길이 제한(104바이트) 때문에 /tmp 에 짧은 이름으로 둔다.
SSH_OPTIONS = (
    "-o", "BatchMode=yes",
    "-o", "ConnectTimeout=5",
    "-o", "ServerAliveInterval=5",
    "-o", "ServerAliveCountMax=3",
    "-o", "ControlMaster=auto",
    "-o", "ControlPath=/tmp/ha-test-%C",
    "-o", "ControlPersist=120",
    "-o", "LogLevel=ERROR",
)

# subprocess 시간 초과를 ssh 의 종료 코드(255)와 구분하려고 timeout(1) 과 같은 값을 쓴다.
TIMEOUT_RC = 124
LOG_SCRIPT_LIMIT = 3000


@dataclass(frozen=True)
class RemoteResult:
    node: str
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0

    def brief(self) -> str:
        message = (self.stderr or self.stdout).strip().replace("\n", " ")
        return f"rc={self.returncode} {message[:200]}"


class Remote:
    def __init__(self, config: Config):
        self.config = config
        self._log: List[str] = []
        self._lock = threading.Lock()

    def run(
        self,
        node: str,
        script: str,
        *,
        as_db_user: bool = False,
        secrets: Optional[Mapping[str, str]] = None,
        timeout: float = 30.0,
    ) -> RemoteResult:
        """스크립트를 실행하고 끝날 때까지 기다린다. 접속 실패와 시간 초과도 예외 대신 결과로 돌려준다.

        secrets 는 export 줄로 스크립트 앞에 붙고 명령 기록에는 남지 않는다.
        """
        started = time.time()
        try:
            completed = subprocess.run(
                self._argv(node, as_db_user),
                input=self._stdin(script, secrets),
                capture_output=True,
                text=True,
                timeout=timeout,
            )
            result = RemoteResult(node, completed.returncode, completed.stdout, completed.stderr)
        except subprocess.TimeoutExpired as expired:
            result = RemoteResult(node, TIMEOUT_RC, _text(expired.stdout), f"{timeout:.0f}초 안에 끝나지 않았다")
        self._record(node, script, as_db_user, result.returncode, time.time() - started, started)
        return result

    def spawn(self, node: str, script: str, *, as_db_user: bool = False) -> "RemoteProcess":
        """끝나기를 기다리지 않고 실행한다. 출력은 줄 단위로 RemoteProcess 가 모은다."""
        started = time.time()
        process = subprocess.Popen(
            self._argv(node, as_db_user),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert process.stdin is not None
        process.stdin.write(self._stdin(script, None))
        process.stdin.close()
        self._record(node, script, as_db_user, None, None, started)
        return RemoteProcess(node, process)

    def drain_log(self) -> str:
        with self._lock:
            text = "\n".join(self._log)
            self._log = []
        return text

    def close(self) -> None:
        """이번 실행에서 연 SSH 제어 연결을 닫는다."""
        for node in self.config.nodes:
            subprocess.run(
                ["ssh", *SSH_OPTIONS, "-O", "exit", node.ssh],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
                check=False,
            )

    def _argv(self, node: str, as_db_user: bool) -> List[str]:
        remote_command = f"sudo -iu {shlex.quote(self.config.db_os_user)} bash -s" if as_db_user else "bash -s"
        return ["ssh", *SSH_OPTIONS, self.config.node(node).ssh, remote_command]

    @staticmethod
    def _stdin(script: str, secrets: Optional[Mapping[str, str]]) -> str:
        exports = "".join(f"export {key}={shlex.quote(value)}\n" for key, value in (secrets or {}).items())
        return f"{exports}main() {{\n{script.rstrip()}\n}}\nmain </dev/null\n"

    def _record(
        self,
        node: str,
        script: str,
        as_db_user: bool,
        returncode: Optional[int],
        seconds: Optional[float],
        started: float,
    ) -> None:
        user = self.config.db_os_user if as_db_user else "ssh 사용자"
        outcome = "백그라운드" if returncode is None else f"rc={returncode} {seconds:.2f}s"
        body = script.rstrip()
        if len(body) > LOG_SCRIPT_LIMIT:
            body = body[:LOG_SCRIPT_LIMIT] + f"\n… ({len(script)}자 중 앞부분만 적었다)"
        indented = "\n".join("    " + line for line in body.splitlines())
        with self._lock:
            self._log.append(f"[{fmt_time(started)}] {node} ({user}) {outcome}\n{indented}")


class RemoteProcess:
    """백그라운드로 실행 중인 원격 스크립트. 출력이 오는 대로 읽어 둔다."""

    def __init__(self, node: str, process: subprocess.Popen):
        self.node = node
        self._process = process
        self._lines: "queue.Queue[Optional[str]]" = queue.Queue()
        self._output: List[str] = []
        self._reader = threading.Thread(target=self._read, daemon=True)
        self._reader.start()

    def _read(self) -> None:
        assert self._process.stdout is not None
        for line in self._process.stdout:
            self._output.append(line)
            self._lines.put(line)
        self._lines.put(None)

    def wait_for_text(self, fragment: str, timeout: float) -> Optional[str]:
        """fragment 가 들어간 줄이 나올 때까지 기다린다. 끝나거나 시간이 지나면 None."""
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            try:
                line = self._lines.get(timeout=remaining)
            except queue.Empty:
                return None
            if line is None:
                return None
            if fragment in line:
                return line

    def finish(self, timeout: float) -> RemoteResult:
        try:
            returncode = self._process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self._process.kill()
            self._process.wait()
            returncode = TIMEOUT_RC
        self._reader.join(timeout=2)
        return RemoteResult(self.node, returncode, "".join(self._output), "")

    def kill(self) -> None:
        if self._process.poll() is None:
            self._process.kill()
            self._process.wait()


def _text(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return str(value)
