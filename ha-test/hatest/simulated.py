# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""모의 클러스터. 실제 클러스터(cluster.Cluster)와 같은 메서드를 갖고, 미리 정한 각본대로 답한다.

서버 없이 시나리오·측정·판정·결과 형식을 끝까지 검증하기 위한 것이다. 실제 측정값이 아니다.
시간은 FakeClock 으로 흐르게 해, 기다림 없이 각본의 시각이 그대로 측정값이 된다.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Set, Tuple

from .clock import FakeClock
from .cluster import ClusterError
from .config import Config
from .model import (
    CpuSample,
    InsertAck,
    Injection,
    Member,
    NodeFacts,
    ProbeAttempt,
    RecorderLogs,
    ReplicationRow,
    RoleSample,
    Snapshot,
    VipMove,
    Visibility,
)


@dataclass
class _NodeState:
    role: str = "replica"
    state: str = "streaming"
    timeline: int = 53
    wal: int = 10_000_000
    patroni_service: str = "active"
    patroni_enabled: str = "enabled"
    openproxy_service: str = "active"
    watchdog: str = "inactive"
    holds_vip: bool = False
    postgres_procs: int = 1
    reachable: bool = True
    patroni_reachable: bool = True
    boot_id: str = "boot-0"


class SimulatedCluster:
    """각본(SimScript)이 정한 시각에 상태를 바꾼다. 시나리오는 이 클래스를 실제 클러스터처럼 쓴다."""

    def __init__(self, config: Config, clock: FakeClock, script: "SimScript"):
        self.config = config
        self.clock = clock
        self.script = script
        self.nodes: Dict[str, _NodeState] = {}
        for name in config.node_names:
            node = _NodeState()
            if name == config.primary:
                node.role, node.state, node.watchdog = "primary", "running", "active"
            if name == config.vip_owner:
                node.holds_vip = True
            self.nodes[name] = node
        self._next_row = 5000
        self._rows: Dict[str, int] = {}  # tag -> row_id, 새 Primary 에 남은 행
        self._documents: Set[int] = set()  # H-2 에서 업로드해 Primary 에 남은 문서

    # 각본 진행: 지금 시각에 해당하는 상태 변화를 모두 반영한다.

    def _advance(self) -> None:
        self.script.apply(self, self.clock.now())

    def set_role(self, node: str, role: str, state: str, timeline: Optional[int] = None) -> None:
        current = self.nodes[node]
        current.role, current.state = role, state
        if timeline is not None:
            current.timeline = timeline
        current.watchdog = "active" if role == "primary" else "inactive"

    # 관찰

    def facts(self, node: str) -> NodeFacts:
        self._advance()
        state = self.nodes[node]
        if not state.reachable:
            return NodeFacts(node=node, reachable=False)
        return NodeFacts(
            node=node,
            reachable=True,
            observed_at=self.clock.now(),
            holds_vip=state.holds_vip,
            patroni_service=state.patroni_service,
            patroni_enabled=state.patroni_enabled,
            openproxy_service=state.openproxy_service if node in self.config.proxy_nodes else None,
            watchdog=state.watchdog,
            boot_id=state.boot_id,
        )

    def _member(self, node: str, at: float) -> Member:
        state = self.nodes[node]
        if not state.reachable or not state.patroni_reachable:
            return Member(node=node, observed_at=at, reachable=False)
        return Member(
            node=node,
            observed_at=at,
            reachable=True,
            role=state.role,
            state=state.state,
            timeline=state.timeline,
            wal_position=state.wal,
        )

    def members(self) -> Dict[str, Member]:
        self._advance()
        at = self.clock.now()
        return {name: self._member(name, at) for name in self.config.node_names}

    def snapshot(self, include_aws: bool = True) -> Snapshot:
        self._advance()
        taken_at = self.clock.now()
        members = {name: self._member(name, taken_at) for name in self.config.node_names}
        facts = {name: self.facts(name) for name in self.config.node_names}
        owner = error = None
        checked = include_aws and self.config.aws_vip_check
        if checked:
            owner, error = self.aws_vip_owner()
        return Snapshot(taken_at, members, facts, owner, checked, error)

    def aws_vip_owner(self) -> Tuple[Optional[str], Optional[str]]:
        self._advance()
        holders = [name for name, state in self.nodes.items() if state.holds_vip]
        return (holders[0], None) if holders else (None, "VIP 가 어느 ENI 에도 붙어 있지 않다")

    def replication(self, node: str) -> List[ReplicationRow]:
        self._advance()
        rows = []
        for name in self.config.node_names:
            state = self.nodes[name]
            if state.role == "replica" and state.state == "streaming":
                rows.append(ReplicationRow(self.config.node(name).member, self.config.node(name).ip, "streaming", "async", 0))
        return sorted(rows, key=lambda r: r.application_name)

    def probe_tags(self, node: str, run_id: str) -> Set[str]:
        self._advance()
        return {tag for tag in self._rows if tag.startswith(f"{run_id}:")}

    def row_count(self, node: str, table: str, row_id: int) -> int:
        self._advance()
        return 1 if row_id in self._documents and node == self.config.primary else 0

    def boot_id(self, node: str) -> Optional[str]:
        self._advance()
        state = self.nodes[node]
        return state.boot_id if state.reachable else None

    def ssh_ok(self, node: str) -> bool:
        self._advance()
        return self.nodes[node].reachable

    def cpu_samples(self, nodes: Sequence[str]) -> Dict[str, CpuSample]:
        self._advance()
        at = self.clock.now()
        return {node: self.script.cpu(self, node, at) for node in nodes}

    # 감시

    def replica_visibility(self, run_id: str, tag: str, replicas: Sequence[str], seconds: int) -> Visibility:
        ack = self.recorder_insert(run_id, tag)
        visible_at = {node: ack.primary_at + self.script.replica_lag_seconds for node in replicas}
        visible_row = {node: ack.row_id for node in replicas}
        return Visibility(insert=ack, visible_at=visible_at, visible_row=visible_row)

    def watch_vip(self, node: str, seconds: int) -> "SimVipWatch":
        return SimVipWatch(self, node, self.clock.now())

    def watch_search_activity(self, nodes: Sequence[str], since: float, seconds: int) -> "SimActivityWatch":
        return SimActivityWatch(self, nodes, seconds)

    # 조작

    def kill_db_stack(self, node: str) -> Injection:
        self._advance()
        at = self.clock.now()
        self.script.on_kill(self, node, at)
        return Injection(node=node, at=at, boot_id=self.nodes[node].boot_id)

    def revive_db_stack(self, node: str) -> float:
        self._advance()
        at = self.clock.now()
        self.script.on_revive(self, node, at)
        return at

    def stop_service(self, node: str, unit: str) -> float:
        self._advance()
        at = self.clock.now()
        self.script.on_stop_service(self, node, unit, at)
        return at

    def start_service(self, node: str, unit: str) -> float:
        self._advance()
        at = self.clock.now()
        self.script.on_start_service(self, node, unit, at)
        return at

    def switchover(self, leader: str, candidate: str) -> float:
        self._advance()
        at = self.clock.now()
        self.script.on_switchover(self, leader, candidate, at)
        return at

    # 기록 도구

    def recorder_open(self, run_id: str) -> None:
        # 모의 실행은 서버에 접속하지 않아 DB 비밀번호가 없어도 된다. .env 없는 팀원도 --simulate 를 돌린다.
        pass

    def recorder_start(self, run_id: str) -> float:
        self._advance()
        return self.clock.now()

    def recorder_insert(self, run_id: str, tag: str) -> InsertAck:
        self._advance()
        at = self.clock.now()
        row_id = self._issue_row(tag, at)
        return InsertAck(tag=tag, row_id=row_id, primary_at=at, acked_at=at + 0.01)

    def recorder_stop(self, run_id: str) -> None:
        self._advance()

    def recorder_recent(self, run_id: str, lines: int = 20) -> List[ProbeAttempt]:
        self._advance()
        now = self.clock.now()
        attempts = [a for a in self.script._probe_log(self, run_id) if a.end <= now]
        return attempts[-lines:]

    def vip_holders(self) -> Dict[str, Optional[bool]]:
        self._advance()
        return {name: (self.nodes[name].holds_vip if self.nodes[name].reachable else None) for name in self.config.proxy_nodes}

    def recorder_logs(self, run_id: str) -> RecorderLogs:
        attempts, roles = self.script.build_logs(self, run_id)
        probe_text = "\n".join(
            f"{a.seq}\t{a.start:.3f}\t{a.end:.3f}\t{'ok' if a.ok else 'fail'}\t{a.detail}" for a in attempts
        )
        roles_text = "\n".join(
            f"{s.tick}\t{s.member.observed_at:.3f}\t{s.member.node}\t"
            f"{'ERR' if not s.member.reachable else s.member.role}" for s in roles
        )
        return RecorderLogs(probe_text, roles_text, attempts, roles)

    def recorder_close(self, run_id: str) -> None:
        pass

    def lock(self, owner: str) -> Optional[str]:
        return None

    def unlock(self) -> None:
        pass

    def close(self) -> None:
        pass

    def drain_command_log(self) -> str:
        return ""

    def add_document(self) -> int:
        """모의 업로드. 각본이 막지 않으면 문서 행이 Primary 에 남는다."""
        self._next_row += 1
        if self.script.write_reaches_primary:
            self._documents.add(self._next_row)
        return self._next_row

    # 내부

    def _issue_row(self, tag: str, at: float) -> int:
        """recorder_insert 로 넣은 표식 행. 장애와 상관없이 남는다(유실은 쓰기 기록 쪽 drop_acked 로 흉내 낸다)."""
        self._next_row += 1
        self._rows[tag] = self._next_row
        return self._next_row

class SimVipWatch:
    def __init__(self, cluster: SimulatedCluster, node: str, started: float):
        self._cluster = cluster
        self._node = node
        self._started = started

    def result(self) -> VipMove:
        return self._cluster.script.vip_move(self._cluster, self._node, self._started)


class SimActivityWatch:
    def __init__(self, cluster: SimulatedCluster, nodes: Sequence[str], seconds: int):
        self._cluster = cluster
        self._nodes = nodes

    def result(self) -> Dict[str, int]:
        return self._cluster.script.activity(self._cluster, self._nodes)


@dataclass
class SimScript:
    """각본. 시각에 따른 상태 변화와 기록 내용을 정한다. 기본값은 모두 성공(Pass)하는 각본이다."""

    replica_lag_seconds: float = 0.2
    # H-3: 실제 클러스터 순서를 따른다. watchdog(20초)이 옛 Primary 를 먼저 재부팅하고, Leader 키 TTL(30초)이
    # 끝나야 승격한다. 옛 Primary 는 자동 시작을 꺼 두었으므로 다시 켜기(revive) 전에는 돌아오지 않는다.
    reboot_after: float = 18.0
    reboot_duration: float = 45.0
    promote_after: float = 30.0
    promote_to: Optional[str] = None
    write_resume_after: float = 33.0
    rejoin_after_revive: float = 15.0
    # H-6: Patroni 를 멈추면 응답이 끊기기까지, 다시 켜면 streaming 으로 돌아오기까지 걸리는 시간.
    patroni_stop_after: float = 1.5
    patroni_start_after: float = 4.0
    # H-5: VIP 이동·쓰기 끊김·복귀.
    vip_move_after: float = 2.0
    vip_write_gap: float = 2.5
    # H-4: 장애 뒤 성공 응답을 받았는데 새 Primary 에 남지 않은(유실된) 행 수. 0 이면 유실 없음.
    drop_acked: int = 0
    # H-2: 검색이 처리된 Replica 쿼리 수, Primary 쿼리 수, Primary CPU.
    replica_queries: int = 24
    primary_queries: int = 0
    primary_cpu: float = 3.0
    replica_cpu: float = 90.0
    write_reaches_primary: bool = True
    # 각본이 기록해 둔 사건: (시각, 함수).
    _events: List[Tuple[float, Callable[["SimulatedCluster"], None]]] = field(default_factory=list)
    _done: Set[int] = field(default_factory=set)
    _injection_at: Optional[float] = None
    _injection_node: Optional[str] = None
    _injection_kind: Optional[str] = None
    _cpu_calls: Dict[str, int] = field(default_factory=dict)

    def apply(self, cluster: SimulatedCluster, now: float) -> None:
        for index, (at, change) in enumerate(self._events):
            if index not in self._done and now >= at:
                change(cluster)
                self._done.add(index)

    def _schedule(self, at: float, change: Callable[["SimulatedCluster"], None]) -> None:
        self._events.append((at, change))

    # H-3 장애: Patroni·PostgreSQL 종료 → watchdog 재부팅 → 승격. 옛 Primary 합류는 revive 가 예약한다.
    def on_kill(self, cluster: SimulatedCluster, node: str, at: float) -> None:
        self._injection_at, self._injection_node, self._injection_kind = at, node, "kill"
        candidate = self.promote_to or next(name for name in cluster.config.replicas if name != node)
        new_tl = cluster.nodes[node].timeline + 1
        state = cluster.nodes[node]
        state.patroni_reachable = False
        state.state = "stopped"
        state.postgres_procs = 0
        state.patroni_service = "failed"
        state.patroni_enabled = "disabled"
        state.watchdog = "active"

        def reboot_start(c: SimulatedCluster) -> None:
            c.nodes[node].reachable = False

        def reboot_end(c: SimulatedCluster) -> None:
            c.nodes[node].reachable = True
            c.nodes[node].boot_id = f"boot-{int(at)}"
            c.nodes[node].patroni_service = "inactive"
            c.nodes[node].watchdog = "inactive"

        def promote(c: SimulatedCluster) -> None:
            c.set_role(candidate, "primary", "running", new_tl)
            c.nodes[candidate].wal = c.nodes[candidate].wal + 1000

        self._schedule(at + self.reboot_after, reboot_start)
        self._schedule(at + self.reboot_after + self.reboot_duration, reboot_end)
        self._schedule(at + self.promote_after, promote)

    def on_revive(self, cluster: SimulatedCluster, node: str, at: float) -> None:
        if not cluster.nodes[node].reachable:
            raise ClusterError(f"{node} Patroni 시작 실패: 재부팅 중이라 접속할 수 없다(모의)")
        state = cluster.nodes[node]
        state.patroni_service, state.patroni_enabled = "active", "enabled"
        state.patroni_reachable = True
        state.role, state.state = "replica", "starting"
        leader_tl = max(n.timeline for n in cluster.nodes.values())

        def rejoin(c: SimulatedCluster) -> None:
            c.nodes[node].postgres_procs = 1
            c.set_role(node, "replica", "streaming", leader_tl)

        self._schedule(at + self.rejoin_after_revive, rejoin)

    def on_stop_service(self, cluster: SimulatedCluster, node: str, unit: str, at: float) -> None:
        if unit == "openproxy":
            self._injection_at, self._injection_node, self._injection_kind = at, node, "proxy"
            cluster.nodes[node].openproxy_service = "inactive"
            other = next(name for name in cluster.config.proxy_nodes if name != node)

            def move_vip(c: SimulatedCluster) -> None:
                c.nodes[node].holds_vip = False
                c.nodes[other].holds_vip = True

            self._schedule(at + self.vip_move_after, move_vip)
        elif unit == "patroni":
            cluster.nodes[node].patroni_service = "inactive"

            def stopped(c: SimulatedCluster) -> None:
                c.nodes[node].patroni_reachable = False
                c.nodes[node].state = "stopped"

            self._schedule(at + self.patroni_stop_after, stopped)

    def on_start_service(self, cluster: SimulatedCluster, node: str, unit: str, at: float) -> None:
        if unit == "openproxy":
            cluster.nodes[node].openproxy_service = "active"

            def restore_vip(c: SimulatedCluster) -> None:
                for name in c.config.proxy_nodes:
                    c.nodes[name].holds_vip = name == c.config.vip_owner

            self._schedule(at + self.vip_move_after, restore_vip)
        elif unit == "patroni":
            cluster.nodes[node].patroni_service = "active"
            cluster.nodes[node].patroni_reachable = True
            cluster.nodes[node].state = "starting"

            def streaming(c: SimulatedCluster) -> None:
                c.set_role(node, "replica", "streaming")

            self._schedule(at + self.patroni_start_after, streaming)

    def on_switchover(self, cluster: SimulatedCluster, leader: str, candidate: str, at: float) -> None:
        new_tl = max(cluster.nodes[leader].timeline, cluster.nodes[candidate].timeline) + 1
        cluster.set_role(leader, "replica", "streaming", new_tl)
        cluster.set_role(candidate, "primary", "running", new_tl)

    def vip_move(self, cluster: SimulatedCluster, node: str, started: float) -> VipMove:
        if cluster.nodes[node].holds_vip or any(e[0] <= started + self.vip_move_after for e in self._events):
            at = started + self.vip_move_after
            return VipMove(node=node, os_at=at, aws_at=at + 1.0)
        return VipMove(node=node, os_at=None, aws_at=None)

    def activity(self, cluster: SimulatedCluster, nodes: Sequence[str]) -> Dict[str, int]:
        result: Dict[str, int] = {}
        for name in nodes:
            if name == cluster.config.primary:
                result[name] = self.primary_queries
            else:
                result[name] = self.replica_queries // max(len(cluster.config.replicas), 1)
        return result

    def cpu(self, cluster: SimulatedCluster, node: str, at: float) -> CpuSample:
        # 호출마다 total 을 100 씩, busy 를 사용률만큼 늘린다. 연이은 두 표본의 차이 비율이 곧 그 사용률이 된다.
        n = self._cpu_calls.get(node, 0)
        self._cpu_calls[node] = n + 1
        percent = self.primary_cpu if node == cluster.config.primary else self.replica_cpu
        return CpuSample(node=node, at=at, busy=int(n * percent), total=n * 100)

    def build_logs(self, cluster: SimulatedCluster, run_id: str) -> Tuple[List[ProbeAttempt], List[RoleSample]]:
        """장애 시각을 기준으로 쓰기·역할 기록을 만든다. 각 TC 가 쓴 시각이 그대로 반영된다."""
        return self._probe_log(cluster, run_id), self._role_log(cluster)

    def _probe_log(self, cluster: SimulatedCluster, run_id: str) -> List[ProbeAttempt]:
        if self._injection_at is None:
            return []
        start = self._injection_at - 2.0
        attempts: List[ProbeAttempt] = []
        seq = 0
        t = start
        gap = self.vip_write_gap if self._injection_kind == "proxy" else self.write_resume_after
        resume_at = self._injection_at + gap
        dropped = 0
        while t < resume_at + 3.0:
            seq += 1
            ok = t < self._injection_at or t >= resume_at
            tag = f"{run_id}:{seq}"
            detail = str(cluster._rows.get(tag, 5000 + seq)) if ok else "rc=1 25006 cannot execute during recovery"
            attempts.append(ProbeAttempt(seq=seq, start=t, end=t + (0.02 if ok else 1.0), ok=ok, detail=detail))
            # 성공 응답인데 새 Primary 에 남기지 않은 행이 drop_acked 만큼 생기면 H-4 가 유실로 잡아낸다.
            if ok:
                if dropped < self.drop_acked and t >= self._injection_at:
                    dropped += 1
                else:
                    cluster._rows.setdefault(tag, 5000 + seq)
            t += 0.5
        return attempts

    def _role_log(self, cluster: SimulatedCluster) -> List[RoleSample]:
        if self._injection_at is None:
            return []
        samples: List[RoleSample] = []
        node = self._injection_node
        candidate = self.promote_to or next(name for name in cluster.config.replicas if name != node)
        old_tl = 53
        for tick in range(0, 50):
            at = self._injection_at - 2.0 + tick
            for name in cluster.config.node_names:
                if name == node and at >= self._injection_at:
                    member = Member(name, at, False)
                elif name == candidate and at >= self._injection_at + self.promote_after:
                    member = Member(name, at, True, "primary", "running", old_tl + 1, cluster.nodes[name].wal)
                elif name == node:
                    member = Member(name, at, True, "primary", "running", old_tl, cluster.nodes[name].wal)
                else:
                    member = Member(name, at, True, "replica", "streaming", old_tl, cluster.nodes[name].wal)
                samples.append(RoleSample(tick=tick, member=member))
        return samples
