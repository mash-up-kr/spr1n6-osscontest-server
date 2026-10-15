# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""시험 하나를 끝까지 돈다: 기준 상태 검사 → 확인 → 기록 시작 → 장애 주입 → 측정 → 원복 → 판정·저장.

중간에 끊기거나(Ctrl-C 포함) 실패해도 기록을 멈추고 VIP 접속 비밀번호 파일을 지우고 원복을 시도한다.
원복이 안 되면 직접 칠 명령을 보여 준다.
"""
from __future__ import annotations

from pathlib import Path
from typing import Callable, Optional

from . import report
from .baseline import Checklist, evaluate
from .clock import stamp
from .config import Config
from .console import Console
from .grafana import GrafanaMarks, NoMarks, monitoring_tunnel_warning
from .report import Outcome, TcResult
from .scenarios import REBOOT_WAIT, SCENARIOS, Scenario, ScenarioError

RESULTS_DIR = Path(__file__).resolve().parent.parent / "results"


class Aborted(Exception):
    """사용자가 멈췄거나 기준 상태가 어긋나 시험을 시작하지 않았다."""


class TestRunner:
    def __init__(
        self,
        config: Config,
        cluster,
        console: Console,
        clock,
        confirm: Callable[[str], bool],
        app_factory=None,
        simulated: bool = False,
        marks: Optional[GrafanaMarks] = None,
    ):
        self.config = config
        self.cluster = cluster
        self.console = console
        self.clock = clock
        self.confirm = confirm
        self.app_factory = app_factory
        self.simulated = simulated
        self.marks = marks or NoMarks(config, console)

    def run(self, tc: str) -> TcResult:
        if tc not in SCENARIOS:
            raise ValueError(f"모르는 시험 항목: {tc}")
        scenario_class = SCENARIOS[tc]
        run_id = f"{tc}-{stamp(self.clock.now())}"
        started = self.clock.now()
        evidence_dir = RESULTS_DIR / run_id

        self.console.section(f"{tc} · {report.TEST_CASES[tc][0]}")
        marks = self.marks.for_tc(tc)
        baseline: Optional[Checklist] = None
        outcome: Optional[Outcome] = None
        restore: Optional[Checklist] = None
        aborted: Optional[str] = None
        locked = False
        # 기준 상태 검사·확인·잠금에서 멈추면 클러스터를 건드리지 않았으니 원복하지 않는다.
        touched = False

        try:
            baseline = self._check_baseline()
            self._confirm_injection(tc, scenario_class)
            locked = self._acquire_lock(run_id)
            touched = True
            outcome = self._execute(tc, scenario_class, run_id, marks)
        except Aborted as stop:
            aborted = str(stop)
            self.console.warn(f"시험을 시작하지 않았다: {aborted}")
        except Exception as error:  # 측정 중 어떤 실패도 원복은 거친다.
            aborted = f"{type(error).__name__}: {error}"
            self.console.warn(f"측정 중 중단: {aborted}")
        finally:
            if touched:
                self._stop_recording(scenario_class, run_id)
                restore = self._restore()
                if restore is not None and restore.ok:
                    marks.mark(self.clock.now(), "원복 완료")
            if locked:
                self._release_lock()

        verdict = report.decide(outcome, aborted)
        if restore is not None and not restore.ok and verdict == report.PASS:
            verdict = report.FAIL
            aborted = (aborted + "; " if aborted else "") + "원복 후 기준 상태로 돌아오지 못함"

        result = TcResult(
            tc=tc,
            verdict=verdict,
            started_at=started,
            baseline=baseline,
            outcome=outcome,
            restore=restore,
            reason=aborted,
            evidence=evidence_dir if not self.simulated else None,
            simulated=self.simulated,
        )
        self._save(result, scenario_class, run_id, evidence_dir)
        self.console.block("")
        self.console.block(report.render_text(result))
        return result

    # 단계

    def _check_baseline(self) -> Checklist:
        self.console.step("기준 상태 검사")
        checklist = evaluate(self.cluster.snapshot(), self.config)
        self.console.info(checklist.summary())
        if not checklist.ok:
            raise Aborted("기준 상태가 아니다. 'ha-test restore' 로 되돌린 뒤 다시 시도한다")
        return checklist

    def _confirm_injection(self, tc: str, scenario_class) -> None:
        # H-1·H-2·H-6 은 관찰만 한다(H-6 의 Replica 정지는 바로 되살린다). 장애를 넣는 TC 만 확인을 받는다.
        if tc not in _DISRUPTIVE:
            return
        if tc in _KILLS_PRIMARY and not self.simulated:
            warning = monitoring_tunnel_warning(self.config, self.config.primary)
            if warning:
                self.console.warn(warning)
        if not self.confirm(f"{tc}: 공용 DB 클러스터에 장애를 주입한다. 진행할까?"):
            raise Aborted("사용자가 취소했다")

    def _acquire_lock(self, run_id: str) -> bool:
        owner = self.cluster.lock(run_id)
        if owner is not None:
            raise Aborted(f"다른 시험이 진행 중이다 (잠금 주인: {owner}). 끊긴 시험의 잠금이면 'ha-test unlock' 으로 푼다")
        return True

    def _execute(self, tc: str, scenario_class, run_id: str, marks: GrafanaMarks) -> Outcome:
        app = None
        if scenario_class.needs_app and self.app_factory is not None:
            app = self.app_factory()
        if scenario_class.needs_recorder:
            self.console.step("기록 시작", f"{self.config.observer} 에서 0.5초마다 VIP 로 INSERT, 1초마다 3대 역할 기록")
            self.cluster.recorder_open(run_id)
            self.cluster.recorder_start(run_id)
            # 장애 전 정상 쓰기가 기록에 남아야 "멈춘 구간"의 시작을 잴 수 있다.
            self.clock.sleep(3)
        scenario: Scenario = scenario_class(self.config, self.cluster, self.console, self.clock, run_id, app, marks)
        return scenario.run()

    def _stop_recording(self, scenario_class, run_id: str) -> None:
        """기록을 멈추고 VIP 접속 비밀번호 파일(.pgpass)을 지운다. 기록 원문은 남겨 증빙으로 가져간다."""
        if not scenario_class.needs_recorder:
            return
        try:
            self.cluster.recorder_stop(run_id)
        except Exception as error:  # 기록 멈춤 실패가 비밀번호 파일 삭제와 원복을 막지 않게 한다.
            self.console.warn(f"기록 멈춤 실패(계속 진행): {error}")
        try:
            self.cluster.recorder_close(run_id)
        except Exception as error:  # 원복은 이어 가되, 남았을 수 있는 비밀번호 파일을 사람이 지우게 알린다.
            self.console.warn(
                f"비밀번호 파일을 지우지 못했다: {error}. "
                f"{self.config.observer} 의 {self.config.remote_dir}/runs/{run_id}/.pgpass 를 직접 지운다"
            )

    def _restore(self) -> Optional[Checklist]:
        self.console.step("원복", "기준 상태로 되돌린다")
        try:
            restore_cluster(self.config, self.cluster, self.console, self.clock)
            checklist = evaluate(self.cluster.snapshot(), self.config)
            self.console.info(checklist.summary())
            if not checklist.ok:
                self._print_manual_restore()
            return checklist
        except Exception as error:
            self.console.warn(f"자동 원복 실패: {error}")
            self._print_manual_restore()
            return None

    def _release_lock(self) -> None:
        try:
            self.cluster.unlock()
        except Exception as error:  # 판정은 이미 끝났다. 남은 잠금은 다음 시험이 알려 주고 unlock 으로 푼다.
            self.console.warn(f"시험 잠금을 풀지 못했다: {error}. 'ha-test unlock' 으로 푼다")

    def _save(self, result: TcResult, scenario_class, run_id: str, evidence_dir: Path) -> None:
        if self.simulated:
            return
        evidence_dir.mkdir(parents=True, exist_ok=True)
        report.write([result], evidence_dir)
        (evidence_dir / "events.log").write_text(self.console.drain_events(), encoding="utf-8")
        (evidence_dir / "commands.log").write_text(self.cluster.drain_command_log(), encoding="utf-8")
        if not scenario_class.needs_recorder:
            return
        try:
            logs = self.cluster.recorder_logs(run_id)
        except Exception as error:  # 판정 결과는 이미 저장했다. 원본 기록만 빠진 사실을 알린다.
            self.console.warn(f"원본 기록(probe.log·roles.log)을 가져오지 못했다: {error}")
            return
        (evidence_dir / "probe.log").write_text(logs.probe_text + "\n", encoding="utf-8")
        (evidence_dir / "roles.log").write_text(logs.roles_text + "\n", encoding="utf-8")

    def _print_manual_restore(self) -> None:
        self.console.warn("자동 원복이 끝나지 않았다. 아래를 직접 확인한다:")
        for line in manual_restore_hint(self.config):
            self.console.info(line)


# 장애를 넣는 TC. 나머지(H-1·H-2·H-6)는 확인 없이 관찰만 한다.
_DISRUPTIVE = {"H-3", "H-4", "H-5"}
# 기준 Primary 노드를 죽이는 TC. 그 노드를 거치는 터널(모니터링)이 함께 끊긴다.
_KILLS_PRIMARY = {"H-3", "H-4"}

# 원복 단계의 기다림 상한.
HEALTHY_WAIT = 180.0       # Patroni 를 다시 켠 노드가 streaming 으로 돌아오기까지(pg_rewind 포함)
SWITCHOVER_WAIT = 90.0     # switchover 뒤 기준 Primary 가 Leader, 나머지가 streaming 이 되기까지
VIP_RETURN_WAIT = 30.0     # OpenProxy 선점으로 VIP 가 기준 주인에게 돌아오기까지


def restore_cluster(config: Config, cluster, console: Console, clock) -> None:
    """기준 상태로 되돌린다. 모든 TC 뒤에, 그리고 'ha-test restore' 에서 같은 절차를 쓴다.

    ① 재부팅 중인 노드는 접속될 때까지 기다린다. ② 꺼졌거나 자동 시작이 꺼진 Patroni 를 켠다.
    ③ 꺼진 OpenProxy 를 켠다. ④ 3대가 streaming 이 되면, Leader 가 기준 Primary 가 아닐 때 switchover 한다.
    ⑤ VIP 가 기준 주인에게 돌아올 때까지 기다린다(OpenProxy 선점으로 저절로 돌아온다).
    """
    started = clock.now()

    def line(text: str) -> None:
        console.info(f"+{clock.now() - started:5.1f}s  {text}")

    for name in config.node_names:
        reported = -999.0
        while not cluster.ssh_ok(name):
            if clock.now() - started > REBOOT_WAIT:
                raise ScenarioError(f"{name} 에 {REBOOT_WAIT:.0f}초 동안 접속하지 못했다")
            if clock.now() - reported >= 10:
                line(f"{name} 접속 대기 (재부팅 중)")
                reported = clock.now()
            clock.sleep(3)

    members = cluster.members()
    for name in config.node_names:
        facts = cluster.facts(name)
        member = members.get(name)
        down = member is None or not member.reachable or member.state == "stopped"
        if down or facts.patroni_service != "active" or facts.patroni_enabled != "enabled":
            line(f"{name} Patroni 켜기 (자동 시작 포함)")
            cluster.revive_db_stack(name)

    for name in config.proxy_nodes:
        if cluster.facts(name).openproxy_service != "active":
            line(f"{name} OpenProxy 켜기")
            cluster.start_service(name, "openproxy")

    if not _wait_until(clock, HEALTHY_WAIT, lambda: _all_streaming(config, cluster.members()), line, "3대 streaming 대기"):
        raise ScenarioError(f"{HEALTHY_WAIT:.0f}초 안에 3대가 streaming 이 되지 않았다")

    leader = _leader(cluster.members())
    if leader != config.primary:
        line(f"switchover {leader} → {config.primary}")
        cluster.switchover(leader, config.primary)
        healthy = lambda: _leader(cluster.members()) == config.primary and _all_streaming(config, cluster.members())
        if not _wait_until(clock, SWITCHOVER_WAIT, healthy, line, f"{config.primary} Leader 복귀 대기"):
            raise ScenarioError(f"switchover 뒤 {SWITCHOVER_WAIT:.0f}초 안에 {config.primary} 가 Leader 가 되지 않았다")

    owner_back = lambda: [n for n, held in cluster.vip_holders().items() if held] == [config.vip_owner]
    if not _wait_until(clock, VIP_RETURN_WAIT, owner_back, line, f"VIP {config.vip_owner} 복귀 대기"):
        raise ScenarioError(f"VIP 가 {VIP_RETURN_WAIT:.0f}초 안에 {config.vip_owner} 로 돌아오지 않았다")
    line("기준 상태로 되돌림")


def _leader(members) -> Optional[str]:
    leaders = [name for name, m in members.items() if m.is_leader]
    return leaders[0] if len(leaders) == 1 else None


def _all_streaming(config: Config, members) -> bool:
    leader = _leader(members)
    if leader is None:
        return False
    return all(m.is_replica and m.state == "streaming" for name, m in members.items() if name != leader)


def _wait_until(clock, limit: float, done: Callable[[], bool], line: Callable[[str], None], label: str) -> bool:
    started = clock.now()
    reported = -999.0
    while clock.now() - started < limit:
        if done():
            return True
        if clock.now() - reported >= 5:
            line(label)
            reported = clock.now()
        clock.sleep(2)
    return done()


def manual_restore_hint(config: Config):
    primary = config.node(config.primary)
    patronictl = f"sudo -iu {config.db_os_user} patronictl -c {config.patroni_config}"
    lines = [f"ssh {config.node(name).ssh} 'sudo systemctl enable --now patroni'" for name in config.node_names]
    lines += [
        f"ssh {config.node(name).ssh} 'sudo systemctl start openproxy'" for name in config.proxy_nodes
    ]
    lines += [
        f"ssh {primary.ssh} \"{patronictl} list\"",
        f"Leader 가 {config.primary} 가 아니면: ssh {primary.ssh} \"{patronictl} switchover --candidate {primary.member} --force\"",
        f"VIP 주인이 {config.vip_owner} 가 아니면 {config.vip_owner} OpenProxy 를 재시작한다",
    ]
    return lines
