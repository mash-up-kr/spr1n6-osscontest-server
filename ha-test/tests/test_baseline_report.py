"""기준 상태 검사와 결과 형식."""
import unittest

from hatest import report
from hatest.baseline import evaluate
from hatest.config import load_config
from hatest.model import Member, NodeFacts, Snapshot
from hatest.report import Criterion, Outcome, TcResult


def config():
    return load_config()


def member(node, role, state, wal=100, tl=53, reachable=True):
    return Member(node, 1000.0, reachable, role, state, tl, wal)


def facts(node, vip=False, op="active", wd="inactive", patroni="active", enabled="enabled"):
    return NodeFacts(
        node=node, reachable=True, observed_at=1000.0, holds_vip=vip, patroni_service=patroni,
        patroni_enabled=enabled, openproxy_service=op, watchdog=wd, boot_id="boot-0",
    )


def baseline_snapshot(cfg, **overrides):
    members = {
        cfg.primary: member(cfg.primary, "primary", "running", wal=200),
        cfg.replicas[0]: member(cfg.replicas[0], "replica", "streaming", wal=200),
        cfg.replicas[1]: member(cfg.replicas[1], "replica", "streaming", wal=200),
    }
    node_facts = {
        cfg.primary: facts(cfg.primary, wd="active"),
        cfg.vip_owner: facts(cfg.vip_owner, vip=True),
        cfg.standby_proxy: facts(cfg.standby_proxy),
    }
    members.update(overrides.get("members", {}))
    node_facts.update(overrides.get("facts", {}))
    return Snapshot(1000.0, members, node_facts, overrides.get("aws", cfg.vip_owner), True, None)


class BaselineTest(unittest.TestCase):
    def setUp(self):
        self.cfg = config()

    def test_baseline_state_passes(self):
        checklist = evaluate(baseline_snapshot(self.cfg), self.cfg)
        self.assertTrue(checklist.ok, checklist.summary())

    def test_lagging_replica_fails(self):
        cfg = self.cfg
        lagged = member(cfg.replicas[0], "replica", "streaming", wal=0)  # Leader 200 과 200B 이상 차이
        snap = baseline_snapshot(cfg, members={cfg.replicas[0]: Member(cfg.replicas[0], 1000.0, True, "replica", "streaming", 53, 200 - 2 * 1024 * 1024)})
        checklist = evaluate(snap, cfg)
        self.assertFalse(checklist.ok)
        self.assertTrue(any(i.label == "Replica" for i in checklist.failures()))

    def test_wrong_leader_fails(self):
        cfg = self.cfg
        snap = baseline_snapshot(cfg, members={
            cfg.primary: member(cfg.primary, "replica", "streaming"),
            cfg.replicas[0]: member(cfg.replicas[0], "primary", "running"),
        })
        checklist = evaluate(snap, cfg)
        self.assertFalse(checklist.ok)

    def test_vip_on_wrong_node_fails(self):
        cfg = self.cfg
        snap = baseline_snapshot(cfg, facts={
            cfg.vip_owner: facts(cfg.vip_owner, vip=False),
            cfg.standby_proxy: facts(cfg.standby_proxy, vip=True),
        }, aws=cfg.standby_proxy)
        self.assertFalse(evaluate(snap, cfg).ok)

    def test_patroni_left_disabled_fails(self):
        # H-3 이 끈 자동 시작을 되돌리지 않으면 원복 검사에서 걸려야 한다.
        cfg = self.cfg
        snap = baseline_snapshot(cfg, facts={cfg.primary: facts(cfg.primary, wd="active", enabled="disabled")})
        self.assertFalse(evaluate(snap, cfg).ok)


class ReportDecideTest(unittest.TestCase):
    def make_outcome(self, all_ok):
        return Outcome(None, "x", [("a", "1")], [Criterion("c1", True, "ok"), Criterion("c2", all_ok, "v")])

    def test_all_criteria_ok_is_pass(self):
        self.assertEqual(report.decide(self.make_outcome(True), aborted=None), report.PASS)

    def test_any_criterion_bad_is_fail(self):
        self.assertEqual(report.decide(self.make_outcome(False), aborted=None), report.FAIL)

    def test_aborted_is_not_tested(self):
        self.assertEqual(report.decide(self.make_outcome(True), aborted="사용자가 취소했다"), report.NOT_TESTED)

    def test_no_outcome_is_not_tested(self):
        self.assertEqual(report.decide(None, aborted=None), report.NOT_TESTED)


class ReportRenderTest(unittest.TestCase):
    def test_text_report_has_every_required_section(self):
        outcome = Outcome(1000.0, "db-01 종료", [("승격까지", "12초")], [Criterion("승격", True, "db-02")])
        result = TcResult("H-3", report.PASS, 1000.0, baseline=None, outcome=outcome, restore=None)
        text = report.render_text(result)
        for section in ("시험항목", "기준 상태", "장애 주입", "측정값", "성공 기준", "판정", "원복 확인"):
            self.assertIn(section, text, f"{section} 항목이 결과 표에서 빠졌다")

    def test_markdown_is_flat_for_notion(self):
        # 노션은 중첩 목록·목록 안 코드블록을 복제한다. 결과 md 는 표 + 한 단계 목록만 써야 한다.
        outcome = Outcome(1000.0, "x", [("a", "1")], [Criterion("c", True, "v")], notes=["주의"])
        md = report.render_markdown(TcResult("H-3", report.PASS, 1000.0, None, outcome, None))
        for line in md.splitlines():
            self.assertFalse(line.startswith("  - ") or line.startswith("    "), f"중첩 목록 줄: {line!r}")


if __name__ == "__main__":
    unittest.main()
