"""노드별 역할·상태·Lag·VIP 표. status 명령과 H-6 의 조회 화면이다."""
from __future__ import annotations

from .clock import fmt_bytes, fmt_time
from .config import Config
from .console import table
from .model import Snapshot


def render(snapshot: Snapshot, config: Config) -> str:
    rows = []
    for node in config.nodes:
        member = snapshot.members[node.name]
        facts = snapshot.facts[node.name]
        lag = "-" if member.is_leader or not member.reachable else fmt_bytes(snapshot.lag_bytes(node.name))
        rows.append(
            [
                node.name,
                node.member,
                member.display_role,
                member.display_state,
                str(member.timeline) if member.timeline is not None else "-",
                lag,
                "보유" if facts.holds_vip else ("-" if facts.reachable else "?"),
                (facts.openproxy_service or "-") if node.name in config.proxy_nodes else "-",
                facts.watchdog or "-",
            ]
        )
    body = table(["노드", "Patroni", "역할", "상태", "TL", "Lag", "VIP", "OpenProxy", "watchdog"], rows)
    holders = ",".join(snapshot.os_vip_holders()) or "없음"
    vip_line = f"VIP {config.vip}:{config.vip_port} → 서버 기준 {holders}"
    if snapshot.aws_vip_checked:
        vip_line += f" · AWS 기준 {snapshot.aws_vip_owner or snapshot.aws_vip_error or '없음'}"
    return f"{body}\n{vip_line}\n조회 시각 {fmt_time(snapshot.taken_at)}"
