"""노드 모니터링 대시보드(Grafana)와 맞물리는 부분.

시험 사건(장애 주입·새 Leader·쓰기 재개·원복)을 대시보드에 세로선(annotation)으로 남겨, 그래프에서 언제 내려가고
언제 올라왔는지 바로 보이게 한다. Grafana 가 꺼져 있어도 시험은 그대로 하고, 못 찍었다는 사실만 한 번 알린다.
"""
from __future__ import annotations

import base64
import json
import subprocess
import urllib.error
import urllib.request
from typing import Optional

from .config import Config
from .console import Console

# 모니터링 compose 의 터널 컨테이너 이름. docker-compose.monitoring.yml 의 container_name 과 같아야 한다.
TUNNEL_CONTAINER = "aidocs-monitoring-cluster-tunnel"


class GrafanaMarks:
    def __init__(self, config: Config, console: Console, tc: str = ""):
        self.config = config
        self.console = console
        self.tc = tc
        self._disabled_reason: Optional[str] = None if config.grafana_url else "GRAFANA_URL 이 비어 있다"
        self._warned = False

    def for_tc(self, tc: str) -> "GrafanaMarks":
        marks = GrafanaMarks(self.config, self.console, tc)
        marks._disabled_reason = self._disabled_reason
        marks._warned = self._warned
        return marks

    def mark(self, at: float, text: str) -> None:
        """at(에포크 초) 에 세로선을 찍는다. 서버 시각을 넘기면 그래프의 변화와 같은 자리에 선다."""
        label = f"{self.tc} {text}".strip()
        if self._disabled_reason:
            self._warn_once()
            return
        payload = json.dumps(
            {
                "dashboardUID": self.config.grafana_dashboard_uid,
                "time": int(at * 1000),
                "tags": ["ha-test", self.tc] if self.tc else ["ha-test"],
                "text": label,
            }
        ).encode()
        token = base64.b64encode(f"{self.config.grafana_user}:{self.config.grafana_password}".encode()).decode()
        request = urllib.request.Request(
            f"{self.config.grafana_url}/api/annotations",
            data=payload,
            headers={"Content-Type": "application/json", "Authorization": f"Basic {token}"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                response.read()
            self.console.info(f"↳ Grafana 세로선: {label}")
        except (urllib.error.URLError, OSError, ValueError) as error:
            self._disabled_reason = f"{self.config.grafana_url} 에 찍지 못했다({str(error)[:60]})"
            self._warn_once()

    def _warn_once(self) -> None:
        if not self._warned:
            self.console.info(f"↳ Grafana 세로선 생략: {self._disabled_reason}. 시험은 그대로 진행한다")
            self._warned = True


class NoMarks(GrafanaMarks):
    """아무것도 찍지 않는다. 테스트와, 표시를 따로 고르지 않은 실행의 기본값이다."""

    def mark(self, at: float, text: str) -> None:
        pass

    def for_tc(self, tc: str) -> "GrafanaMarks":
        return self


class SimMarks(GrafanaMarks):
    """모의 실행용. 보내지 않고 어디에 찍힐지만 보여 준다."""

    def mark(self, at: float, text: str) -> None:
        self.console.info(f"↳ Grafana 세로선(모의): {self.tc} {text}".rstrip())

    def for_tc(self, tc: str) -> "GrafanaMarks":
        return SimMarks(self.config, self.console, tc)


def monitoring_tunnel_warning(config: Config, node: str) -> Optional[str]:
    """모니터링 터널이 node 를 거치면 경고 문장을 돌려준다. 확인할 수 없으면 None.

    터널은 SSH_HOST 한 노드를 거쳐 3대의 지표를 모두 가져온다. 그 노드를 죽이는 시험(H-3·H-4) 중에는
    대시보드 전체가 끊긴다. 로컬 docker·ssh 설정만 읽고 서버에는 접속하지 않는다.
    """
    try:
        inspected = subprocess.run(
            ["docker", "inspect", TUNNEL_CONTAINER, "--format", "{{range .Config.Env}}{{println .}}{{end}}"],
            capture_output=True, text=True, timeout=5, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if inspected.returncode != 0:
        return None
    tunnel_host = next((line.split("=", 1)[1] for line in inspected.stdout.splitlines() if line.startswith("SSH_HOST=")), None)
    node_host = _ssh_hostname(config.node(node).ssh)
    if not tunnel_host or not node_host or tunnel_host != node_host:
        return None
    observer_host = _ssh_hostname(config.node(config.observer).ssh) or "<관찰 노드 공인 IP>"
    return (
        f"모니터링 터널이 {node}({tunnel_host})를 거친다. 이 시험 중에는 Grafana 대시보드 전체가 끊긴다. "
        f"모니터링을 {config.observer} 경유로 다시 띄운다: "
        f"SSH_HOST={observer_host} docker compose -f docker-compose.monitoring.yml up -d"
    )


def _ssh_hostname(target: str) -> Optional[str]:
    try:
        resolved = subprocess.run(["ssh", "-G", target], capture_output=True, text=True, timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return next((line.split()[1] for line in resolved.stdout.splitlines() if line.startswith("hostname ")), None)
