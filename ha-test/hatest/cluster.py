"""실제 클러스터. 시나리오가 쓰는 관찰·조작·기록 도구 기능을 SSH 원격 실행으로 제공한다.

모의 클러스터(simulated.SimulatedCluster)도 같은 메서드를 갖는다. 시나리오는 둘을 구분하지 않는다.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

from . import commands
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
    Snapshot,
    VipMove,
    Visibility,
)
from .parse import (
    ParseError,
    parse_activity,
    parse_cpu,
    parse_insert,
    parse_key_values,
    parse_marker_watch,
    parse_patroni_scan,
    parse_probe_log,
    parse_recorder_logs,
    parse_replication,
    parse_vip_watch,
)
from .remote import Remote, RemoteProcess

RECORDER_PATH = Path(__file__).resolve().parent.parent / "node" / "recorder.sh"

# 감시 스크립트가 시작됐다고 알리기까지 기다리는 시간. SSH 접속과 sudo·psql 시작을 넉넉히 덮는다.
WATCH_READY_SECONDS = 15


class ClusterError(Exception):
    """원격 명령이 실패해 시험을 이어 갈 수 없다."""


class Cluster:
    def __init__(self, config: Config, remote: Remote, clock, recorder_path: Path = RECORDER_PATH):
        self.config = config
        self.remote = remote
        self.clock = clock
        self._recorder_path = recorder_path
        self._recorder_deployed = False

    # 관찰

    def facts(self, node: str) -> NodeFacts:
        result = self.remote.run(node, commands.node_facts(self.config), timeout=15)
        if not result.ok:
            return NodeFacts(node=node, reachable=False)
        values = parse_key_values(result.stdout)
        facts = NodeFacts(
            node=node,
            reachable=True,
            observed_at=_maybe_float(values.get("ts")),
            holds_vip=values.get("vip") == "1",
            patroni_service=values.get("patroni_service") or None,
            patroni_enabled=values.get("patroni_enabled") or None,
            openproxy_service=values.get("openproxy_service") or None,
            watchdog=values.get("watchdog") or None,
            boot_id=values.get("boot_id") or None,
        )
        return facts

    def members(self) -> Dict[str, Member]:
        """세 노드의 Patroni 상태. 관찰 노드에서 조회하고, 그 노드가 안 되면 다른 노드에서 조회한다."""
        errors = []
        for viewer in self._viewers():
            result = self.remote.run(viewer, commands.patroni_scan(self.config), timeout=10)
            if result.ok:
                found = parse_patroni_scan(result.stdout)
                return {name: found.get(name, Member(name, None, False)) for name in self.config.node_names}
            errors.append(f"{viewer} {result.brief()}")
        raise ClusterError("어느 노드에서도 Patroni 를 조회하지 못했다: " + " / ".join(errors))

    def snapshot(self, include_aws: bool = True) -> Snapshot:
        taken_at = self.clock.now()
        with ThreadPoolExecutor(max_workers=len(self.config.nodes) + 1) as pool:
            facts_futures = {name: pool.submit(self.facts, name) for name in self.config.node_names}
            members_future = pool.submit(self.members)
            facts = {name: future.result() for name, future in facts_futures.items()}
            members = members_future.result()
        owner = error = None
        checked = include_aws and self.config.aws_vip_check
        if checked:
            owner, error = self.aws_vip_owner()
        return Snapshot(
            taken_at=taken_at,
            members=members,
            facts=facts,
            aws_vip_owner=owner,
            aws_vip_checked=checked,
            aws_vip_error=error,
        )

    def aws_vip_owner(self) -> Tuple[Optional[str], Optional[str]]:
        """(AWS 가 VIP 를 붙여 둔 ENI 의 노드, 확인하지 못한 이유). OpenProxy 노드마다 메타데이터를 읽는다."""
        nodes = self.config.proxy_nodes
        with ThreadPoolExecutor(max_workers=len(nodes)) as pool:
            futures = {node: pool.submit(self.remote.run, node, commands.aws_vip_held(self.config), timeout=15) for node in nodes}
            results = {node: future.result() for node, future in futures.items()}
        holders, errors = [], []
        for node, result in results.items():
            value = parse_key_values(result.stdout).get("aws_vip") if result.ok else None
            if value == "1":
                holders.append(node)
            elif value != "0":
                errors.append(f"{node} 메타데이터 확인 실패({result.brief() if not result.ok else value})")
        if errors:
            return None, " / ".join(errors)
        if len(holders) == 1:
            return holders[0], None
        if not holders:
            return None, "VIP 가 어느 ENI 에도 붙어 있지 않다"
        return None, "VIP 가 두 ENI 에 동시에 보인다: " + ",".join(holders)

    def replication(self, node: str) -> List[ReplicationRow]:
        return parse_replication(self._db_query(node, commands.replication(self.config), "pg_stat_replication"))

    def probe_tags(self, node: str, run_id: str) -> Set[str]:
        output = self._db_query(node, commands.probe_tags(self.config, run_id), "기록 행 조회")
        return {line.strip() for line in output.splitlines() if line.strip()}

    def row_count(self, node: str, table: str, row_id: int) -> int:
        return int(self._db_query(node, commands.row_count(self.config, table, row_id), f"{table} 조회").strip() or 0)

    def boot_id(self, node: str) -> Optional[str]:
        result = self.remote.run(node, commands.boot_id(), timeout=10)
        if not result.ok:
            return None
        return result.stdout.strip() or None

    def ssh_ok(self, node: str) -> bool:
        return self.remote.run(node, commands.ssh_ok(), timeout=10).ok

    def cpu_samples(self, nodes: Sequence[str]) -> Dict[str, CpuSample]:
        with ThreadPoolExecutor(max_workers=len(nodes)) as pool:
            futures = {node: pool.submit(self.remote.run, node, commands.cpu_sample(), timeout=10) for node in nodes}
            results = {node: future.result() for node, future in futures.items()}
        failed = [f"{node} {result.brief()}" for node, result in results.items() if not result.ok]
        if failed:
            raise ClusterError("CPU 를 읽지 못했다: " + " / ".join(failed))
        return {node: parse_cpu(node, result.stdout) for node, result in results.items()}

    # 감시

    def replica_visibility(self, run_id: str, tag: str, replicas: Sequence[str], seconds: int) -> Visibility:
        """Replica 마다 감시를 띄운 뒤 VIP 로 표식 행을 넣고, 각 Replica 에서 그 행이 보인 시각을 잰다."""
        watches = {node: self.remote.spawn(node, commands.marker_watch(self.config, tag, seconds), as_db_user=True) for node in replicas}
        try:
            self._wait_ready(watches, "ready")
            ack = self.recorder_insert(run_id, tag)
            results = {node: watch.finish(timeout=seconds + 15) for node, watch in watches.items()}
        finally:
            for watch in watches.values():
                watch.kill()
        visible_at: Dict[str, Optional[float]] = {}
        visible_row: Dict[str, Optional[int]] = {}
        for node, result in results.items():
            _, visible, row_id = parse_marker_watch(result.stdout)
            visible_at[node] = visible
            visible_row[node] = row_id
        return Visibility(insert=ack, visible_at=visible_at, visible_row=visible_row)

    def watch_vip(self, node: str, seconds: int) -> "VipWatch":
        """node 가 VIP 를 갖게 되는 순간을 그 노드 안에서 잰다. 장애를 넣기 전에 띄운다."""
        script = commands.vip_watch(self.config, seconds, self.config.aws_vip_check)
        watch = self.remote.spawn(node, script)
        self._wait_ready({node: watch}, "ready=")
        return VipWatch(node, watch, seconds)

    def watch_search_activity(self, nodes: Sequence[str], since: float, seconds: int) -> "ActivityWatch":
        watches = {node: self.remote.spawn(node, commands.activity_watch(self.config, since, seconds), as_db_user=True) for node in nodes}
        self._wait_ready(watches, "ready=")
        return ActivityWatch(watches, seconds)

    # 조작

    def kill_db_stack(self, node: str) -> Injection:
        result = self.remote.run(node, commands.kill_db_stack(), timeout=30)
        values = parse_key_values(result.stdout)
        if "ts" not in values:
            raise ClusterError(f"{node} 장애 주입 실패: {result.brief()}")
        return Injection(node=node, at=float(values["ts"]), boot_id=values.get("boot_id"))

    def revive_db_stack(self, node: str) -> float:
        return self._timed_action(node, commands.revive_db_stack(), f"{node} Patroni 시작")

    def stop_service(self, node: str, unit: str) -> float:
        return self._timed_action(node, commands.stop_service(unit), f"{node} {unit} 중단")

    def start_service(self, node: str, unit: str) -> float:
        return self._timed_action(node, commands.start_service(unit), f"{node} {unit} 시작")

    def switchover(self, leader: str, candidate: str) -> float:
        script = commands.switchover(self.config, self.config.node(leader).member, self.config.node(candidate).member)
        return self._timed_action(candidate, script, f"switchover {leader} → {candidate}", as_db_user=True, timeout=90)

    # 기록 도구 (관찰 노드)

    def recorder_open(self, run_id: str) -> None:
        """기록 도구를 올리고 VIP 접속용 비밀번호 파일을 만든다. 끝나면 recorder_close 로 지운다."""
        password = self.config.require_db_password()
        if not self._recorder_deployed:
            content = self._recorder_path.read_text(encoding="utf-8")
            self._observer_call(commands.recorder_deploy(self.config, content), "기록 도구 배포")
            self._recorder_deployed = True
        self._observer_call(
            commands.recorder_call(self.config, "prepare", self._run_dir(run_id)),
            "기록 도구 준비",
            secrets={"HA_DB_PASSWORD": password},
        )

    def recorder_start(self, run_id: str) -> float:
        output = self._observer_call(commands.recorder_call(self.config, "start", self._run_dir(run_id), run_id), "기록 시작")
        return float(parse_key_values(output)["started"])

    def recorder_insert(self, run_id: str, tag: str) -> InsertAck:
        output = self._observer_call(commands.recorder_call(self.config, "insert", self._run_dir(run_id), tag), "VIP INSERT")
        return parse_insert(tag, output)

    def recorder_stop(self, run_id: str) -> None:
        self._observer_call(commands.recorder_call(self.config, "stop", self._run_dir(run_id)), "기록 멈춤", timeout=30)

    def recorder_recent(self, run_id: str, lines: int = 20) -> List[ProbeAttempt]:
        """쓰기 기록 끝부분. 진행 표시용이라 읽다 걸린 줄은 버리고 실패를 내지 않는다."""
        result = self.remote.run(
            self.config.observer, commands.recorder_tail(self.config, run_id, lines), as_db_user=True, timeout=10
        )
        if not result.ok:
            return []
        attempts = []
        for line in result.stdout.splitlines():
            try:
                attempts.extend(parse_probe_log(line))
            except ParseError:
                continue
        return attempts

    def vip_holders(self) -> Dict[str, Optional[bool]]:
        """OpenProxy 노드마다 VIP 를 갖고 있는지. 접속이 안 되면 None."""
        with ThreadPoolExecutor(max_workers=len(self.config.proxy_nodes)) as pool:
            futures = {node: pool.submit(self.remote.run, node, commands.vip_held(self.config), timeout=10) for node in self.config.proxy_nodes}
            results = {node: future.result() for node, future in futures.items()}
        return {node: (parse_key_values(r.stdout).get("vip") == "1") if r.ok else None for node, r in results.items()}

    def recorder_logs(self, run_id: str) -> RecorderLogs:
        probes = self._observer_call(commands.recorder_read(self.config, run_id, "probe.log"), "쓰기 기록 읽기")
        roles = self._observer_call(commands.recorder_read(self.config, run_id, "roles.log"), "역할 기록 읽기")
        return parse_recorder_logs(probes, roles)

    def recorder_close(self, run_id: str) -> None:
        self.remote.run(
            self.config.observer,
            commands.recorder_call(self.config, "cleanup", self._run_dir(run_id)),
            as_db_user=True,
            timeout=30,
        )

    # 잠금

    def lock(self, owner: str) -> Optional[str]:
        """잠그면 None, 이미 누가 잠갔으면 그 내용을 돌려준다."""
        output = self._observer_call(commands.lock(self.config, owner), "시험 잠금")
        values = parse_key_values(output)
        return None if values.get("locked") == "1" else (values.get("owner") or "알 수 없음")

    def unlock(self) -> None:
        self._observer_call(commands.unlock(self.config), "시험 잠금 풀기")

    def close(self) -> None:
        self.remote.close()

    def drain_command_log(self) -> str:
        return self.remote.drain_log()

    # 내부

    def _viewers(self) -> Iterable[str]:
        yield self.config.observer
        for name in self.config.node_names:
            if name != self.config.observer:
                yield name

    def _run_dir(self, run_id: str) -> str:
        return f"{self.config.remote_dir}/runs/{run_id}"

    def _db_query(self, node: str, script: str, what: str) -> str:
        result = self.remote.run(node, script, as_db_user=True, timeout=30)
        if not result.ok:
            raise ClusterError(f"{node} {what} 실패: {result.brief()}")
        return result.stdout

    def _observer_call(self, script: str, what: str, secrets=None, timeout: float = 30.0) -> str:
        result = self.remote.run(self.config.observer, script, as_db_user=True, secrets=secrets, timeout=timeout)
        if not result.ok:
            raise ClusterError(f"{self.config.observer} {what} 실패: {result.brief()}")
        return result.stdout

    def _timed_action(self, node: str, script: str, what: str, as_db_user: bool = False, timeout: float = 60.0) -> float:
        result = self.remote.run(node, script, as_db_user=as_db_user, timeout=timeout)
        values = parse_key_values(result.stdout)
        if not result.ok or "ts" not in values:
            raise ClusterError(f"{what} 실패: {result.brief()}")
        return float(values["ts"])

    @staticmethod
    def _wait_ready(watches: Dict[str, RemoteProcess], fragment: str) -> None:
        for node, watch in watches.items():
            if watch.wait_for_text(fragment, WATCH_READY_SECONDS) is None:
                for other in watches.values():
                    other.kill()
                output = watch.finish(timeout=1).stdout.strip()[:200]
                raise ClusterError(f"{node} 감시를 시작하지 못했다: {output}")


class VipWatch:
    def __init__(self, node: str, watch: RemoteProcess, seconds: int):
        self._node = node
        self._watch = watch
        self._seconds = seconds

    def result(self) -> VipMove:
        output = self._watch.finish(timeout=self._seconds + 20).stdout
        os_at, aws_at = parse_vip_watch(output)
        return VipMove(node=self._node, os_at=os_at, aws_at=aws_at)


class ActivityWatch:
    def __init__(self, watches: Dict[str, RemoteProcess], seconds: int):
        self._watches = watches
        self._seconds = seconds

    def result(self) -> Dict[str, int]:
        return {node: len(parse_activity(watch.finish(timeout=self._seconds + 20).stdout)) for node, watch in self._watches.items()}


def _maybe_float(value: Optional[str]) -> Optional[float]:
    try:
        return float(value) if value else None
    except ValueError:
        return None
