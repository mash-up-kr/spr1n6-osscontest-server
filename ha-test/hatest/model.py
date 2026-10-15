# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""클러스터에서 읽은 상태와 기록을 담는 값."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Mapping, Optional

# Patroni REST /patroni 의 role 값. 4.0 은 primary, 그 전 버전은 master 를 쓴다.
LEADER_ROLES = frozenset({"primary", "master"})
REPLICA_ROLES = frozenset({"replica"})


@dataclass(frozen=True)
class Member:
    """Patroni 가 자기 노드에 대해 답한 상태. 응답이 없으면 reachable 이 False 다."""

    node: str
    observed_at: Optional[float]
    reachable: bool
    role: Optional[str] = None
    state: Optional[str] = None
    timeline: Optional[int] = None
    # Leader 는 현재 WAL 위치, Replica 는 재생한 위치다. 둘의 차이가 Lag 다.
    wal_position: Optional[int] = None

    @property
    def is_leader(self) -> bool:
        return self.reachable and self.role in LEADER_ROLES

    @property
    def is_replica(self) -> bool:
        return self.reachable and self.role in REPLICA_ROLES

    @property
    def is_running(self) -> bool:
        return self.reachable and self.state in ("running", "streaming")

    @property
    def display_role(self) -> str:
        if not self.reachable:
            return "-"
        if self.is_leader:
            return "Leader"
        if self.is_replica:
            return "Replica"
        return self.role or "-"

    @property
    def display_state(self) -> str:
        return self.state if self.reachable and self.state else "응답 없음"


@dataclass(frozen=True)
class NodeFacts:
    """SSH 로 노드에 들어가 본 사실."""

    node: str
    reachable: bool
    observed_at: Optional[float] = None
    holds_vip: Optional[bool] = None
    patroni_service: Optional[str] = None
    patroni_enabled: Optional[str] = None
    openproxy_service: Optional[str] = None
    watchdog: Optional[str] = None
    boot_id: Optional[str] = None


@dataclass(frozen=True)
class Snapshot:
    taken_at: float
    members: Mapping[str, Member]
    facts: Mapping[str, NodeFacts]
    aws_vip_owner: Optional[str] = None
    aws_vip_checked: bool = False
    aws_vip_error: Optional[str] = None

    def leaders(self) -> List[str]:
        return sorted(name for name, member in self.members.items() if member.is_leader)

    def leader(self) -> Optional[str]:
        leaders = self.leaders()
        return leaders[0] if len(leaders) == 1 else None

    def lag_bytes(self, node: str) -> Optional[int]:
        return lag_bytes(self.members, node)

    def os_vip_holders(self) -> List[str]:
        return sorted(name for name, facts in self.facts.items() if facts.holds_vip)


def lag_bytes(members: Mapping[str, Member], node: str) -> Optional[int]:
    """Leader WAL 위치와 그 노드의 재생 위치 차이. Leader 가 하나가 아니거나 값이 없으면 None."""
    leaders = [member for member in members.values() if member.is_leader]
    member = members.get(node)
    if len(leaders) != 1 or member is None or not member.is_replica:
        return None
    if leaders[0].wal_position is None or member.wal_position is None:
        return None
    return max(leaders[0].wal_position - member.wal_position, 0)


@dataclass(frozen=True)
class ReplicationRow:
    application_name: str
    client_addr: str
    state: str
    sync_state: str
    replay_lag_bytes: Optional[int]


@dataclass(frozen=True)
class ProbeAttempt:
    """기록 도구가 VIP 로 보낸 INSERT 한 번."""

    seq: int
    start: float
    end: float
    ok: bool
    detail: str

    def tag(self, run_id: str) -> str:
        return f"{run_id}:{self.seq}"


@dataclass(frozen=True)
class RoleSample:
    """기록 도구가 1초마다 남긴 노드 역할. 같은 tick 은 같은 순간에 3대를 본 것이다."""

    tick: int
    member: Member


@dataclass(frozen=True)
class RecorderLogs:
    """기록 도구 원문과 읽은 값. 원문은 증빙으로 남긴다."""

    probe_text: str
    roles_text: str
    attempts: List[ProbeAttempt]
    roles: List[RoleSample]


@dataclass(frozen=True)
class CpuSample:
    node: str
    at: float
    busy: int
    total: int


@dataclass(frozen=True)
class InsertAck:
    """VIP 로 넣은 행 하나. primary_at 은 Primary 시계로 INSERT 한 시각, acked_at 은 응답을 받은 시각이다."""

    tag: str
    row_id: int
    primary_at: float
    acked_at: float


@dataclass(frozen=True)
class Visibility:
    """H-1. Replica 마다 표식 행이 보인 시각과 그 행의 id."""

    insert: InsertAck
    visible_at: Dict[str, Optional[float]]
    visible_row: Dict[str, Optional[int]]


@dataclass(frozen=True)
class VipMove:
    """VIP 가 한 노드로 옮겨진 시각. 서버(eth0)와 AWS(ENI) 를 따로 잰다. 옮겨지지 않았으면 None."""

    node: str
    os_at: Optional[float]
    aws_at: Optional[float]


@dataclass(frozen=True)
class Injection:
    node: str
    at: float
    boot_id: Optional[str] = None
