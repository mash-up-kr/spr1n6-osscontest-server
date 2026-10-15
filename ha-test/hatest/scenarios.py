# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""TC 별 시나리오. 장애를 넣고 측정해 성공 기준을 판정한 Outcome 을 돌려준다.

장애를 넣은 뒤에는 상태가 바뀔 때까지 몇 초마다 한 줄씩 지금 상태(Leader·쓰기·VIP)를 찍는다. 시연에서 내려가고
올라오는 과정이 터미널에 그대로 보이게 하기 위해서다. 같은 순간을 Grafana 대시보드에 세로선으로도 남긴다.
판정에 쓰는 값은 진행 표시가 아니라 기록 도구의 서버 시각 기록에서 다시 계산한다.
"""
from __future__ import annotations

from typing import Callable, Dict, Optional, Tuple

from . import measure, status
from .appclient import UploadResult
from .clock import fmt_bytes, fmt_seconds
from .config import Config
from .console import Console
from .grafana import GrafanaMarks, NoMarks
from .model import Member
from .report import Criterion, Outcome

# 성공 기준의 시간 한계. 기능테스트 시험 절차의 성공 기준 값이다.
PROMOTE_LIMIT = 45.0       # H-3 "쓰기 멈춤 45초 이내"
VIP_WRITE_LIMIT = 5.0      # H-5 "쓰기 끊김 5초 이내"
REPLICATION_LIMIT = 1.0    # H-1 "1초 안에 조회"

# 진행 표시에서 연속 실패를 세려고 읽는 쓰기 기록 줄 수. 0.5초 간격이라 최근 60초 분량이다.
PROGRESS_TAIL = 120

# 기다림의 상한. 넘기면 그 기준은 Fail 이다.
FOLLOW_MARGIN = 20.0       # 승격·쓰기 재개를 한계(45초)보다 이만큼 더 지켜본다. 넘긴 값도 측정해 보이기 위해서다.
REBOOT_WAIT = 240.0        # watchdog 재부팅 뒤 SSH 가 돌아오기까지. EC2 재부팅은 보통 1분 안팎이다.
REJOIN_LIMIT = 180.0       # 옛 Primary 가 다시 켜진 뒤 streaming Replica 가 되기까지. pg_rewind 를 덮는다.
VIP_FOLLOW_LIMIT = 30.0    # VIP 인수·복귀를 지켜보는 시간
H6_LIMIT = 60.0            # H-6 정지·복귀가 반영되기까지
WATCH_SECONDS = 90         # 노드 안 감시 스크립트를 띄워 두는 시간
SEARCH_SECONDS = 20        # H-2 검색 부하 시간
PRIMARY_CPU_LIMIT = 30.0   # H-2 "Primary CPU 가 오르지 않음"의 판정선(%). 근거는 판정 줄 위 주석에 있다
CLOCK_MARGIN = 2.0         # H-2 감시 시작 시각은 Mac 시계다. 서버 시계와의 차이를 덮으려고 이만큼 앞당겨 찾는다
PROGRESS_INTERVAL = 2.0    # 진행 한 줄을 찍는 간격


class ScenarioError(Exception):
    """시나리오를 끝까지 수행하지 못했다. 판정은 NT 가 된다."""


class Scenario:
    needs_recorder = False
    needs_app = False

    def __init__(self, config: Config, cluster, console: Console, clock, run_id: str, app=None, marks: Optional[GrafanaMarks] = None):
        self.config = config
        self.cluster = cluster
        self.console = console
        self.clock = clock
        self.run_id = run_id
        self.app = app
        self.marks = marks or NoMarks(config, console)

    def run(self) -> Outcome:
        raise NotImplementedError

    def _tag(self, suffix: str) -> str:
        return f"{self.run_id}:{suffix}"

    # 진행 표시

    def _line(self, started: float, text: str) -> None:
        self.console.info(f"+{self.clock.now() - started:5.1f}s  {text}")

    def _write_state(self, since: float) -> Tuple[str, bool]:
        """(쓰기 상태 문구, since 뒤에 실패했다가 다시 성공했는가)."""
        recent = [a for a in self.cluster.recorder_recent(self.run_id, PROGRESS_TAIL) if a.start >= since]
        if not recent:
            return "쓰기 확인 중", False
        recent.sort(key=lambda a: a.start)
        failed_once = any(not a.ok for a in recent)
        last = recent[-1]
        if last.ok:
            return ("쓰기 재개 ✔" if failed_once else "쓰기 정상"), failed_once
        streak = 0
        for attempt in reversed(recent):
            if attempt.ok:
                break
            streak += 1
        return f"쓰기 실패 중(연속 {streak}회)", False

    def _members_text(self, members: Dict[str, Member]) -> str:
        parts = []
        for name in self.config.node_names:
            member = members[name]
            parts.append(f"{name} {member.display_role if member.reachable else '응답 없음'}")
        return " · ".join(parts)

    def _wait_ssh(self, node: str) -> None:
        """watchdog 재부팅 중이면 SSH 가 돌아올 때까지 기다린다."""
        started = self.clock.now()
        reported = -999.0
        while not self.cluster.ssh_ok(node):
            elapsed = self.clock.now() - started
            if elapsed > REBOOT_WAIT:
                raise ScenarioError(f"{node} 가 {REBOOT_WAIT:.0f}초 안에 접속되지 않는다")
            if elapsed - reported >= 10:
                self._line(started, f"{node} 접속 대기 (watchdog 재부팅 중)")
                reported = elapsed
            self.clock.sleep(3)


class ReplicationScenario(Scenario):
    """H-1. VIP 로 표식 행을 넣고 두 Replica 에서 같은 행이 1초 안에 보이는지, 복제가 streaming·Lag 0 인지 본다."""

    needs_recorder = True

    def run(self) -> Outcome:
        tag = self._tag("marker")
        self.console.step("표식 행 INSERT 및 Replica 조회", f"VIP {self.config.vip}:{self.config.vip_port} · tag={tag}")
        visibility = self.cluster.replica_visibility(self.run_id, tag, self.config.replicas, seconds=WATCH_SECONDS)
        self.marks.mark(visibility.insert.primary_at, f"표식 행 INSERT (id {visibility.insert.row_id})")

        delays: Dict[str, Optional[float]] = {
            node: measure.seconds_between(visibility.insert.primary_at, visibility.visible_at.get(node))
            for node in self.config.replicas
        }
        for node, delay in delays.items():
            self.console.info(f"{node} 에서 같은 행 조회: {fmt_seconds(delay, 3)} 뒤 (id {visibility.visible_row.get(node)})")

        self.console.step("복제 상태 조회", f"{self.config.primary} pg_stat_replication")
        rows = self.cluster.replication(self.config.primary)
        for row in rows:
            self.console.info(f"{row.application_name} {row.client_addr} {row.state} {row.sync_state} Lag {fmt_bytes(row.replay_lag_bytes)}")

        within = all(delay is not None and delay <= REPLICATION_LIMIT for delay in delays.values())
        same_row = all(visibility.visible_row.get(node) == visibility.insert.row_id for node in self.config.replicas)
        streaming = [r for r in rows if r.state == "streaming"]
        lag_zero = bool(rows) and all(r.replay_lag_bytes is not None and r.replay_lag_bytes < 1024 * 1024 for r in rows)
        delay_text = ", ".join(f"{node} {fmt_seconds(delay, 3)}" for node, delay in delays.items())
        lag_text = ", ".join(f"{r.application_name} {fmt_bytes(r.replay_lag_bytes)}" for r in rows) or "-"

        return Outcome(
            injected_at=visibility.insert.primary_at,
            injection=f"표식 행 INSERT (VIP 경유, id {visibility.insert.row_id})",
            measurements=[
                ("Replica 조회까지", delay_text),
                ("pg_stat_replication", f"{len(streaming)}줄 streaming"),
                ("Lag", lag_text),
            ],
            criteria=[
                Criterion("두 Replica 모두 같은 행이 1초 안에 조회됨", within and same_row, f"{delay_text}, 같은 id {'예' if same_row else '아니오'}"),
                Criterion("pg_stat_replication 2줄 streaming", len(streaming) == len(self.config.replicas), f"{len(streaming)}줄"),
                Criterion("Lag 0MB (1MB 미만)", lag_zero, lag_text),
            ],
        )


class ReadDistributionScenario(Scenario):
    """H-2. 검색 부하를 걸어 쿼리가 Replica 에서만 처리되는지·Primary CPU 가 안 오르는지 보고, 업로드(쓰기)는 Primary 에서 처리되는지 본다."""

    needs_app = True

    def run(self) -> Outcome:
        if not self.app.reachable():
            raise ScenarioError(f"server 에 접속하지 못했다: {self.config.app_url}. dev 프로필로 띄워 둔다")

        nodes = self.config.node_names
        primary = self.config.primary
        self.console.step("① 검색 쿼리 감시 시작", "3대 pg_stat_activity")
        since = self.clock.now()
        watch = self.cluster.watch_search_activity(nodes, since - CLOCK_MARGIN, SEARCH_SECONDS + 5)
        cpu_start = self.cluster.cpu_samples(nodes)
        self.marks.mark(since, "검색 부하 시작")

        self.console.step("② 웹·MCP 검색 반복 실행", f"{SEARCH_SECONDS}초")
        web, mcp = self._load(since)

        cpu_end = self.cluster.cpu_samples(nodes)
        self.marks.mark(self.clock.now(), "검색 부하 끝")
        by_node = watch.result()
        cpu = measure.cpu_window(cpu_start, cpu_end)
        primary_q = by_node.get(primary, 0)
        replica_q = sum(by_node.get(node, 0) for node in self.config.replicas)
        primary_cpu = cpu.get(primary)
        cpu_text = ", ".join(f"{node} {cpu[node]:.0f}%" for node in nodes if cpu.get(node) is not None)
        self.console.info("검색 쿼리 처리: " + ", ".join(f"{node} {by_node.get(node, 0)}건" for node in nodes))

        self.console.step("③ 문서 업로드(쓰기)", f"POST /api/v1/documents → {primary} 에서 행 조회")
        uploaded, rows_on_primary = self._upload_and_find()

        searched = web.ok_count > 0 and mcp.ok_count > 0
        written = uploaded.ok and rows_on_primary == 1
        return Outcome(
            injected_at=since,
            injection=f"검색 부하 {SEARCH_SECONDS}초 (웹·MCP) + 문서 업로드 1건",
            measurements=[
                ("검색 요청", f"웹 {web}, MCP {mcp}"),
                ("검색 쿼리 처리", ", ".join(f"{node} {by_node.get(node, 0)}건" for node in nodes)),
                ("부하 중 CPU", cpu_text or "-"),
                ("업로드", f"{uploaded.detail}, documentId {uploaded.document_id or '-'}, {primary} 에서 {rows_on_primary}건"),
            ],
            criteria=[
                Criterion(
                    f"검색 쿼리가 모두 {'·'.join(self.config.replicas)} 에서 처리됨 (Primary 0건)",
                    searched and primary_q == 0 and replica_q > 0,
                    f"Primary {primary_q}건, Replica {replica_q}건 (웹 성공 {web.ok_count}, MCP 성공 {mcp.ok_count})",
                ),
                # 앱의 다른 요청(Job 폴링 등)이 늘 조금은 있어 0% 를 요구하지 않는다. 검색이 Primary 로 가면 Replica 처럼 수십 % 로 오른다.
                Criterion(
                    "검색 부하 중 Primary CPU 가 오르지 않음 (30% 미만)",
                    primary_cpu is not None and primary_cpu < PRIMARY_CPU_LIMIT,
                    f"{primary_cpu:.0f}%" if primary_cpu is not None else "측정 실패",
                ),
                Criterion(
                    "쓰기(문서 업로드)는 Primary 에서 처리됨",
                    written,
                    f"{uploaded.detail}, {primary} 에 행 {rows_on_primary}건",
                ),
            ],
            notes=["Replica 는 읽기 전용이라 쓰기를 받지 못한다. 업로드가 성공하고 그 행이 Primary 에 있으면 Primary 가 처리한 것이다.",
                   "시험용으로 올린 문서는 확인 뒤 지운다."],
        )

    def _upload_and_find(self) -> Tuple[UploadResult, int]:
        content = f"# ha-test H-2 쓰기 확인\n\n실행 {self.run_id}\n".encode()
        uploaded = self.app.upload(f"ha-test-{self.run_id}.md", content, f"ha-test {self.run_id}")
        if not uploaded.ok or uploaded.document_id is None:
            self.console.warn(f"업로드 실패: {uploaded.detail}. MinIO 를 띄웠는지 본다")
            return uploaded, 0
        rows = self.cluster.row_count(self.config.primary, "document", uploaded.document_id)
        self.console.info(f"documentId {uploaded.document_id} → {self.config.primary} 에서 {rows}건 조회")
        deleted = self.app.delete(uploaded.document_id)
        if not deleted.ok:
            self.console.warn(f"시험 문서를 지우지 못했다({deleted.detail}). documentId {uploaded.document_id} 를 직접 지운다")
        return uploaded, rows

    def _load(self, started: float) -> Tuple["LoadCount", "LoadCount"]:
        web, mcp = LoadCount(), LoadCount()
        deadline = started + SEARCH_SECONDS
        index = 0
        last_line = started
        while self.clock.now() < deadline:
            query = self.config.search_queries[index % len(self.config.search_queries)]
            web.add(self.app.web_search(query).ok)
            mcp.add(self.app.mcp_search(query).ok)
            index += 1
            if self.clock.now() - last_line >= 5:
                self._line(started, f"검색 웹 {web} · MCP {mcp}")
                last_line = self.clock.now()
            self.clock.sleep(0.2)
        return web, mcp


class LoadCount:
    def __init__(self) -> None:
        self.ok_count = 0
        self.failed = 0

    def add(self, ok: bool) -> None:
        if ok:
            self.ok_count += 1
        else:
            self.failed += 1

    def __str__(self) -> str:
        return f"{self.ok_count + self.failed}회(성공 {self.ok_count})"


class PrimaryFailureScenario(Scenario):
    """H-3·H-4 공통. 기준 Primary 를 강제 종료하고, 새 Leader 가 뜨고 쓰기가 다시 될 때까지 지켜본다."""

    needs_recorder = True

    def _kill_and_follow(self) -> Tuple[float, Optional[str], Optional[str]]:
        """(주입 시각, 새 Leader, 주입 전 boot_id). 새 Leader 가 끝내 안 뜨면 None."""
        old = self.config.primary
        self.console.step("장애 주입", f"{old} Patroni 자동 시작을 끄고 Patroni·PostgreSQL 강제 종료")
        injection = self.cluster.kill_db_stack(old)
        self.marks.mark(injection.at, f"장애 주입: {old} 강제 종료")
        self.console.info(f"이제 {PROGRESS_INTERVAL:.0f}초마다 상태를 찍는다. Grafana '노드별 상태 변화'에도 같은 변화가 보인다")

        started = self.clock.now()
        new_leader: Optional[str] = None
        resumed = False
        while self.clock.now() - started < PROMOTE_LIMIT + FOLLOW_MARGIN:
            members = self.cluster.members()
            leaders = [name for name, m in members.items() if m.is_leader and name != old]
            write_text, resumed_now = self._write_state(injection.at)
            first_seen = bool(leaders) and new_leader is None
            if first_seen:
                new_leader = leaders[0]
                self.marks.mark(members[new_leader].observed_at or self.clock.now(), f"새 Leader {new_leader}")
            star = " ★ 승격" if first_seen else ""
            self._line(started, f"Leader {leaders[0] if leaders else '없음'}{star} · {self._members_text(members)} · {write_text}")
            if resumed_now and not resumed:
                resumed = True
                self.marks.mark(self.clock.now(), "쓰기 재개")
            if new_leader and resumed:
                break
            self.clock.sleep(PROGRESS_INTERVAL)
        return injection.at, new_leader, injection.boot_id


class FailoverScenario(PrimaryFailureScenario):
    """H-3. 사람 개입 없이 Replica 가 승격해 쓰기가 45초 안에 재개되고, 옛 Primary 가 Replica 로 합류하는지 본다."""

    def run(self) -> Outcome:
        old = self.config.primary
        injected_at, new_leader, boot_before = self._kill_and_follow()

        # 절차 ④: 옛 Primary 를 다시 켜고 Replica 로 합류하는 시간을 잰다. switchover 는 원복 단계가 한다.
        self.console.step("옛 Primary 다시 시작", f"{old} (watchdog 재부팅 중이면 접속될 때까지 기다린다)")
        self._wait_ssh(old)
        boot_after = self.cluster.boot_id(old)
        revived_at = self.cluster.revive_db_stack(old)
        self.marks.mark(revived_at, f"{old} Patroni 다시 시작")
        rejoined_at = self._follow_rejoin(old)
        if rejoined_at is not None:
            self.marks.mark(rejoined_at, f"{old} Replica 합류")

        logs = self.cluster.recorder_logs(self.run_id)
        promoted = measure.promotion(logs.roles, old, injected_at)
        stop = measure.write_stop(logs.attempts, injected_at)
        max_leaders = measure.max_simultaneous_leaders(logs.roles)
        promote_delay = measure.seconds_between(injected_at, promoted[1]) if promoted else None
        rejoin_delay = measure.seconds_between(revived_at, rejoined_at)
        rebooted = boot_before is not None and boot_after is not None and boot_before != boot_after

        notes = [
            f"측정값은 기록 도구가 {self.config.observer} 에서 0.5초마다 VIP 로 INSERT 하고 1초마다 3대 역할을 적은 서버 시각 기록으로 계산했다.",
            f"{old} watchdog 재부팅: {'확인됨 (boot_id 바뀜) — 승격 전에 옛 Primary 가 fencing 됐다' if rebooted else '없음'}",
        ]
        return Outcome(
            injected_at=injected_at,
            injection=f"{old} Patroni 자동 시작 끄고 Patroni·PostgreSQL SIGKILL",
            measurements=[
                ("승격까지", fmt_seconds(promote_delay) + (f" (→ {promoted[0]})" if promoted else "")),
                ("쓰기 멈춤", fmt_seconds(stop.duration) + f" (실패 시도 {stop.failures}회)"),
                ("옛 Primary 합류", fmt_seconds(rejoin_delay) + " (다시 시작 → streaming)"),
            ],
            criteria=[
                Criterion("사람 개입 없이 Replica 1대가 Leader 로 승격", promoted is not None and new_leader is not None, f"{promoted[0]} 승격" if promoted else "승격 안 됨"),
                Criterion("Leader 는 항상 1대", max_leaders <= 1, f"동시에 Leader 최대 {max_leaders}대"),
                Criterion("쓰기 멈춤 45초 이내 후 자동 재개", stop.duration is not None and stop.duration <= PROMOTE_LIMIT, fmt_seconds(stop.duration)),
                Criterion("옛 Primary 가 Replica 로 합류", rejoined_at is not None, fmt_seconds(rejoin_delay)),
            ],
            notes=notes,
        )

    def _follow_rejoin(self, node: str) -> Optional[float]:
        started = self.clock.now()
        while self.clock.now() - started < REJOIN_LIMIT:
            member = self.cluster.members()[node]
            role = f"{member.display_role} " if member.reachable else ""
            self._line(started, f"{node} {role}{member.display_state}")
            if member.is_replica and member.state == "streaming":
                return member.observed_at
            self.clock.sleep(PROGRESS_INTERVAL)
        return None


class DataRetentionScenario(PrimaryFailureScenario):
    """H-4. 쓰기 중 Primary 장애를 내고, 성공 응답을 받은 행이 새 Primary 에 모두 남았는지·유실 건수를 본다."""

    def run(self) -> Outcome:
        marker = self._tag("before-failure")
        self.console.step("장애 전 표식 행 저장", f"tag={marker}")
        marker_ack = self.cluster.recorder_insert(self.run_id, marker)
        self.clock.sleep(2)

        injected_at, new_leader, _ = self._kill_and_follow()
        if new_leader is None:
            raise ScenarioError("새 Primary 가 뜨지 않아 유실을 비교할 수 없다")

        self.console.step("유실 비교", f"성공 응답 받은 행 ↔ 새 Primary {new_leader} 에 남은 행")
        logs = self.cluster.recorder_logs(self.run_id)
        present = self.cluster.probe_tags(new_leader, self.run_id)
        result = measure.loss(logs.attempts, present, self.run_id, injected_at)
        marker_kept = marker in present
        self.console.info(f"성공 응답 {result.acked}건 · 남은 행 {result.present}건 · 유실 {len(result.lost)}건 · 모르게 커밋 {len(result.unknown_commits)}건")
        self.console.info(f"장애 전 표식 행(id {marker_ack.row_id}) 새 Primary 에서 {'조회됨' if marker_kept else '없음'}")

        notes = [
            "비동기 복제라 장애 순간에 커밋 중이던 행은 유실될 수 있어 건수를 함께 기록한다.",
            "모르게 커밋은 실패 응답을 받았지만 실제로는 남은 행이다. 데이터가 사라진 것이 아니다.",
        ]
        if result.lost:
            notes.append("유실된 행: " + ", ".join(result.lost[:10]))
        return Outcome(
            injected_at=injected_at,
            injection=f"쓰기(0.5초마다 INSERT) 중 {self.config.primary} 강제 종료",
            measurements=[
                ("성공 응답", f"{result.acked}건"),
                ("남은 행", f"{result.present}건"),
                ("유실", f"{len(result.lost)}건 (장애 전 응답분 {len(result.lost_before)}건)"),
                ("모르게 커밋", f"{len(result.unknown_commits)}건"),
            ],
            criteria=[
                Criterion("장애 전에 저장한 행이 새 Primary 에서 모두 조회됨", marker_kept and not result.lost_before, f"표식 행 {'있음' if marker_kept else '없음'}, 장애 전 응답분 유실 {len(result.lost_before)}건"),
                Criterion("유실 0건", not result.lost, f"유실 {len(result.lost)}건"),
            ],
            notes=notes,
        )


class ProxyFailoverScenario(Scenario):
    """H-5. Active OpenProxy 를 멈추고 Standby 가 VIP 를 같은 Endpoint 로 인수하는지, 쓰기 끊김이 5초 이내인지 본다."""

    needs_recorder = True

    def run(self) -> Outcome:
        active, standby = self.config.vip_owner, self.config.standby_proxy

        self.console.step("VIP 인수 감시 시작", f"{standby} 안에서 eth0·AWS ENI 를 지켜본다")
        takeover_watch = self.cluster.watch_vip(standby, seconds=WATCH_SECONDS)
        self.console.step("장애 주입", f"{active} OpenProxy 중단")
        down_at = self.cluster.stop_service(active, "openproxy")
        self.marks.mark(down_at, f"장애 주입: {active} OpenProxy 중단")
        self._follow_vip(standby, down_at)
        move = takeover_watch.result()
        if move.os_at is not None:
            self.marks.mark(move.os_at, f"VIP → {standby}")

        self.console.step("같은 Endpoint 로 접속·쓰기", f"{self.config.vip}:{self.config.vip_port}")
        ack = self.cluster.recorder_insert(self.run_id, self._tag("after-takeover"))
        self.console.info(f"INSERT 성공 (id {ack.row_id})")

        self.console.step("원복", f"{active} OpenProxy 시작 → VIP 자동 복귀")
        return_watch = self.cluster.watch_vip(active, seconds=WATCH_SECONDS)
        up_at = self.cluster.start_service(active, "openproxy")
        self.marks.mark(up_at, f"{active} OpenProxy 시작")
        self._follow_vip(active, up_at)
        back = return_watch.result()
        if back.os_at is not None:
            self.marks.mark(back.os_at, f"VIP → {active} 복귀")

        self.clock.sleep(3)
        logs = self.cluster.recorder_logs(self.run_id)
        takeover_stop = measure.write_stop(logs.attempts, down_at, until=up_at)
        return_stop = measure.write_stop(logs.attempts, up_at)
        moved = move.os_at is not None and (move.aws_at is not None or not self.config.aws_vip_check)
        returned = back.os_at is not None

        return Outcome(
            injected_at=down_at,
            injection=f"{active} OpenProxy 중단 (systemctl stop)",
            measurements=[
                ("VIP 이동", f"서버 {fmt_seconds(measure.seconds_between(down_at, move.os_at))} · AWS {fmt_seconds(measure.seconds_between(down_at, move.aws_at))}"),
                ("쓰기 끊김", fmt_seconds(takeover_stop.duration)),
                ("복귀 끊김", fmt_seconds(return_stop.duration) + f" (VIP 복귀 {fmt_seconds(measure.seconds_between(up_at, back.os_at))})"),
            ],
            criteria=[
                Criterion(f"Standby({standby})가 VIP 를 인수하고 같은 Endpoint 로 접속됨", moved and ack.row_id > 0, f"VIP {self.config.vip}:{self.config.vip_port} → {standby}, INSERT id {ack.row_id}"),
                Criterion("쓰기 끊김 5초 이내", takeover_stop.duration is not None and takeover_stop.duration <= VIP_WRITE_LIMIT, fmt_seconds(takeover_stop.duration)),
                Criterion("복귀 때도 자동", returned, f"{active} 로 복귀 {fmt_seconds(measure.seconds_between(up_at, back.os_at))}" if returned else "복귀 안 됨"),
            ],
            notes=["앱이 붙는 주소(VIP)는 그대로다. VIP 를 가진 OpenProxy 노드만 바뀐다."],
        )

    def _follow_vip(self, target: str, since: float) -> None:
        started = self.clock.now()
        while self.clock.now() - started < VIP_FOLLOW_LIMIT:
            holders = self.cluster.vip_holders()
            owners = [name for name, held in holders.items() if held]
            write_text, _ = self._write_state(since)
            self._line(started, f"VIP {','.join(owners) or '없음'} · {write_text}")
            if owners == [target] and write_text != "쓰기 확인 중" and not write_text.startswith("쓰기 실패"):
                return
            self.clock.sleep(1.0)


class NodeStatusScenario(Scenario):
    """H-6. 노드별 역할·상태·Lag 를 조회하고, Replica 1대를 멈췄다 되살려 상태 변화가 반영되는지 본다."""

    def run(self) -> Outcome:
        node = self.config.h6_stop_node
        self.console.step("① 기준 상태 조회", "노드별 역할·상태·Lag")
        before = self.cluster.snapshot()
        self.console.block(status.render(before, self.config))

        self.console.step("② Replica 1대 정지", f"{node} Patroni stop")
        stopped_at = self.cluster.stop_service(node, "patroni")
        self.marks.mark(stopped_at, f"{node} Patroni 정지")
        reflected_at = self._follow(node, lambda m: not m.is_running, "정지 반영")
        during = self.cluster.snapshot(include_aws=False)
        self.console.block(status.render(during, self.config))

        self.console.step("③ 원복", f"{node} Patroni 시작")
        started_at = self.cluster.start_service(node, "patroni")
        self.marks.mark(started_at, f"{node} Patroni 시작")
        back_at = self._follow(node, lambda m: m.is_replica and m.state == "streaming", "streaming 복귀")
        after = self.cluster.snapshot()
        self.console.block(status.render(after, self.config))

        shown = all(before.members[n].reachable and before.members[n].state for n in self.config.node_names)
        lag_shown = all(before.lag_bytes(n) is not None for n in self.config.replicas)
        reflected = reflected_at is not None and not during.members[node].is_running
        restored = back_at is not None and after.members[node].is_replica and after.members[node].state == "streaming"

        return Outcome(
            injected_at=stopped_at,
            injection=f"{node} Patroni stop → start",
            measurements=[
                ("정지 → 반영", fmt_seconds(measure.seconds_between(stopped_at, reflected_at))),
                ("시작 → streaming 복귀", fmt_seconds(measure.seconds_between(started_at, back_at))),
                ("정지 중 표시", f"{node} {during.members[node].display_state}"),
            ],
            criteria=[
                Criterion("3대의 역할·상태·Lag 가 표시됨", shown and lag_shown, "역할·상태·TL·Lag 3대 표시" if shown and lag_shown else "일부 조회 실패"),
                Criterion("멈춘 노드의 상태 변화가 반영됨", reflected, f"{node} {during.members[node].display_state}"),
                Criterion("원복 뒤 기준 상태로 표시됨", restored, f"{node} {after.members[node].display_role} {after.members[node].display_state}"),
            ],
            notes=["같은 변화가 Grafana '노드별 상태 변화' 패널에도 빨간 구간으로 남는다(대시보드 새로고침 5초)."],
        )

    def _follow(self, node: str, done: Callable[[Member], bool], label: str) -> Optional[float]:
        started = self.clock.now()
        while self.clock.now() - started < H6_LIMIT:
            member = self.cluster.members()[node]
            role = f"{member.display_role} " if member.reachable else ""
            self._line(started, f"{node} {role}{member.display_state}")
            if done(member):
                self.console.info(f"{label} ✔")
                return member.observed_at
            self.clock.sleep(0.5)
        return None


SCENARIOS: Dict[str, type] = {
    "H-1": ReplicationScenario,
    "H-2": ReadDistributionScenario,
    "H-3": FailoverScenario,
    "H-4": DataRetentionScenario,
    "H-5": ProxyFailoverScenario,
    "H-6": NodeStatusScenario,
}
