# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""모의 클러스터로 6개 TC 를 끝까지 돌린다. 흐름·측정·판정·원복이 함께 맞물리는지 본다."""
import io
import unittest

from hatest import report
from hatest.appclient import SimAppClient
from hatest.clock import FakeClock
from hatest.config import load_config
from hatest.console import Console
from hatest.grafana import GrafanaMarks, SimMarks
from hatest.runner import TestRunner
from hatest.scenarios import SCENARIOS
from hatest.simulated import SimScript, SimulatedCluster


def config():
    return load_config(environ={"HA_DB_PASSWORD": "test-pass"})


def run(tc, script=None, stream=None):
    cfg = config()
    clock = FakeClock()
    console = Console(clock, stream=stream or io.StringIO())
    cluster = SimulatedCluster(cfg, clock, script or SimScript())
    runner = TestRunner(
        cfg, cluster, console, clock,
        confirm=lambda prompt: True,
        app_factory=lambda: SimAppClient(cfg, cluster),
        simulated=True,
        marks=SimMarks(cfg, console),
    )
    return runner.run(tc)


class HappyPathTest(unittest.TestCase):
    def test_every_test_case_passes_on_healthy_script(self):
        for tc in sorted(SCENARIOS):
            result = run(tc)
            self.assertEqual(result.verdict, report.PASS, f"{tc} 판정 {result.verdict}, 이유 {result.reason}")
            self.assertTrue(result.restore.ok, f"{tc} 원복 뒤 기준 상태로 돌아오지 못함")


class FailurePathTest(unittest.TestCase):
    def test_write_stop_over_45s_fails_h3(self):
        # 쓰기 재개가 45초 한계를 넘으면 Fail 이어야 한다. 기본 각본(33초)은 Pass 다.
        self.assertEqual(run("H-3", SimScript(write_resume_after=60.0)).verdict, report.FAIL)
        self.assertEqual(run("H-3", SimScript()).verdict, report.PASS)

    def test_slow_vip_takeover_fails_h5(self):
        self.assertEqual(run("H-5", SimScript(vip_move_after=8.0, vip_write_gap=9.0)).verdict, report.FAIL)

    def test_data_loss_fails_h4(self):
        self.assertEqual(run("H-4", SimScript(drop_acked=2)).verdict, report.FAIL)

    def test_search_reaching_primary_fails_h2(self):
        self.assertEqual(run("H-2", SimScript(primary_queries=5)).verdict, report.FAIL)

    def test_upload_not_found_on_primary_fails_h2(self):
        # 명세 H-2 세 번째 기준: 쓰기(업로드)는 Primary 에서 처리돼야 한다. 행이 Primary 에 없으면 Fail 이다.
        result = run("H-2", SimScript(write_reaches_primary=False))
        self.assertEqual(result.verdict, report.FAIL)
        failed = [c.text for c in result.outcome.criteria if not c.ok]
        self.assertEqual(failed, ["쓰기(문서 업로드)는 Primary 에서 처리됨"])


class SecretCleanupTest(unittest.TestCase):
    def _runner(self, cluster, cfg, clock):
        return TestRunner(
            cfg, cluster, Console(clock, stream=io.StringIO()), clock,
            confirm=lambda prompt: True,
            app_factory=lambda: SimAppClient(cfg, cluster),
            simulated=True,
        )

    def test_password_file_removed_even_when_interrupted(self):
        # Ctrl-C 로 끊겨도 db-03 의 VIP 접속 비밀번호 파일(.pgpass)을 지워야 한다.
        cfg, clock = config(), FakeClock()
        cluster = SimulatedCluster(cfg, clock, SimScript())
        closed = []
        cluster.recorder_close = lambda run_id: closed.append(run_id)

        def interrupted(node):
            raise KeyboardInterrupt

        cluster.kill_db_stack = interrupted
        with self.assertRaises(KeyboardInterrupt):
            self._runner(cluster, cfg, clock).run("H-3")
        self.assertEqual(len(closed), 1, "끊긴 시험이 비밀번호 파일을 지우지 않았다")

    def test_nothing_touched_when_baseline_differs(self):
        # 기준 상태가 아니면 시작하지 않는다. 기록 도구도 원복도 건드리지 않는다.
        cfg, clock = config(), FakeClock()
        cluster = SimulatedCluster(cfg, clock, SimScript())
        cluster.set_role(cfg.primary, "replica", "streaming")
        cluster.set_role(cfg.replicas[0], "primary", "running")
        calls = []
        cluster.recorder_close = lambda run_id: calls.append("close")
        cluster.revive_db_stack = lambda node: calls.append("revive")
        result = self._runner(cluster, cfg, clock).run("H-3")
        self.assertEqual(result.verdict, report.NOT_TESTED)
        self.assertEqual(calls, [])


class NoPasswordSimulationTest(unittest.TestCase):
    def test_simulation_runs_without_db_password(self):
        # .env 가 없는 팀원도 --simulate 로 흐름을 볼 수 있어야 한다. 모의 실행은 서버에 접속하지 않는다.
        from pathlib import Path

        cfg = load_config(environ={}, env_path=Path("/nonexistent/.env"))
        self.assertIsNone(cfg.db_password)
        clock = FakeClock()
        cluster = SimulatedCluster(cfg, clock, SimScript())
        runner = TestRunner(
            cfg, cluster, Console(clock, stream=io.StringIO()), clock,
            confirm=lambda prompt: True,
            app_factory=lambda: SimAppClient(cfg, cluster),
            simulated=True,
        )
        self.assertEqual(runner.run("H-3").verdict, report.PASS)


class OldPrimaryTest(unittest.TestCase):
    def test_old_primary_never_returns_by_itself_after_kill(self):
        # 장애 주입이 Patroni 자동 시작을 끄므로 옛 Primary 는 다시 켜기 전에는 돌아오지 않는다.
        # 모의 클러스터가 이를 지켜야 H-3 의 "옛 Primary 합류" 측정이 다시 켜기를 빠뜨렸을 때 Fail 로 드러난다.
        cfg = config()
        clock = FakeClock()
        cluster = SimulatedCluster(cfg, clock, SimScript())
        cluster.kill_db_stack(cfg.primary)
        clock.sleep(600)
        member = cluster.members()[cfg.primary]
        self.assertFalse(member.reachable, "옛 Primary 가 다시 켜지 않았는데 돌아왔다")

    def test_h3_measures_rejoin_from_revive(self):
        stream = io.StringIO()
        result = run("H-3", stream=stream)
        self.assertIn("옛 Primary 다시 시작", stream.getvalue())
        rejoin = dict(result.outcome.measurements)["옛 Primary 합류"]
        self.assertTrue(rejoin.startswith("15.") or rejoin.startswith("16."), rejoin)

    def test_restore_waits_for_rebooting_node(self):
        # H-4 는 옛 Primary 를 직접 다시 켜지 않는다. 재부팅이 끝나기를 원복이 기다려야 기준 상태로 돌아온다.
        stream = io.StringIO()
        result = run("H-4", stream=stream)
        self.assertTrue(result.restore.ok)
        self.assertIn("접속 대기", stream.getvalue())


class ProgressTest(unittest.TestCase):
    def test_h3_prints_live_state_until_writes_resume(self):
        stream = io.StringIO()
        run("H-3", stream=stream)
        text = stream.getvalue()
        self.assertIn("Leader 없음", text)
        self.assertIn("★ 승격", text)
        self.assertIn("쓰기 실패 중", text)
        self.assertIn("쓰기 재개 ✔", text)

    def test_grafana_marks_shown_for_key_events(self):
        stream = io.StringIO()
        run("H-3", stream=stream)
        text = stream.getvalue()
        for event in ("장애 주입", "새 Leader", "쓰기 재개", "원복 완료"):
            self.assertIn(f"Grafana 세로선(모의): H-3 {event}", text)


class GrafanaUnavailableTest(unittest.TestCase):
    def test_unreachable_grafana_warns_once_and_does_not_raise(self):
        cfg = load_config(environ={"HA_GRAFANA_URL": "http://127.0.0.1:9"})
        stream = io.StringIO()
        marks = GrafanaMarks(cfg, Console(FakeClock(), stream=stream), "H-3")
        marks.mark(1.0, "a")
        marks.mark(2.0, "b")
        self.assertEqual(stream.getvalue().count("세로선 생략"), 1)


if __name__ == "__main__":
    unittest.main()


class MonitoringTunnelWarningTest(unittest.TestCase):
    def _fake_run(self, tunnel_host):
        from unittest import mock

        def fake(argv, **kwargs):
            if argv[0] == "docker":
                return mock.Mock(returncode=0, stdout=f"PATH=/bin\nSSH_HOST={tunnel_host}\n")
            hosts = {"ha-db-01": "203.0.113.11", "ha-db-03": "203.0.113.13"}
            return mock.Mock(returncode=0, stdout=f"user rocky\nhostname {hosts[argv[2]]}\n")

        return fake

    def test_warns_when_dashboard_tunnel_goes_through_killed_node(self):
        from unittest import mock

        from hatest import grafana

        with mock.patch.object(grafana.subprocess, "run", side_effect=self._fake_run("203.0.113.11")):
            warning = grafana.monitoring_tunnel_warning(config(), "db-01")
        self.assertIn("대시보드 전체가 끊긴다", warning)
        self.assertIn("SSH_HOST=203.0.113.13", warning)

    def test_no_warning_when_tunnel_uses_observer(self):
        from unittest import mock

        from hatest import grafana

        with mock.patch.object(grafana.subprocess, "run", side_effect=self._fake_run("203.0.113.13")):
            self.assertIsNone(grafana.monitoring_tunnel_warning(config(), "db-01"))
