# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""측정·판정 로직. 고정 입력으로 값을 확인한다. 각 단언이 깨지면 무엇이 틀린 것인지 이름에 적는다."""
import unittest

from hatest import measure
from hatest.model import CpuSample, Member, ProbeAttempt, RoleSample


def probe(seq, start, ok, end=None):
    return ProbeAttempt(seq=seq, start=start, end=end if end is not None else start + 0.02, ok=ok, detail="")


def role(tick, at, node, leader):
    member = Member(node, at, True, "primary" if leader else "replica", "running" if leader else "streaming", 54, 100)
    return RoleSample(tick=tick, member=member)


class WriteStopTest(unittest.TestCase):
    def test_stop_spans_last_success_to_first_success_after_failures(self):
        # 100.0 성공, 100.5~113.5 실패, 114.0 다시 성공 → 멈춤은 100.0 응답부터 114.0 응답까지.
        attempts = [probe(1, 100.0, True)]
        attempts += [probe(s, 100.5 + i * 0.5, False, end=101.5 + i * 0.5) for i, s in enumerate(range(2, 28))]
        attempts.append(probe(28, 114.0, True))
        stop = measure.write_stop(attempts, since=100.4)
        self.assertAlmostEqual(stop.duration, 114.02 - 100.02, places=2)
        self.assertGreater(stop.failures, 0)
        self.assertIsNotNone(stop.resumed_at)

    def test_no_failure_measures_largest_ack_gap(self):
        # 실패가 없으면 성공 응답 사이 최대 간격(기록 주기 언저리)을 멈춤으로 본다.
        attempts = [probe(1, 100.0, True), probe(2, 100.5, True), probe(3, 103.0, True)]
        stop = measure.write_stop(attempts, since=100.4)
        self.assertAlmostEqual(stop.duration, 103.02 - 100.52, places=2)
        self.assertEqual(stop.failures, 0)

    def test_never_resumed_leaves_duration_none(self):
        attempts = [probe(1, 100.0, True)] + [probe(s, 100.5 + i * 0.5, False) for i, s in enumerate(range(2, 10))]
        stop = measure.write_stop(attempts, since=100.4)
        self.assertIsNone(stop.duration)
        self.assertIsNone(stop.resumed_at)


class PromotionTest(unittest.TestCase):
    def test_finds_first_new_leader_after_injection(self):
        samples = [
            role(1, 100.0, "db-01", True), role(1, 100.0, "db-02", False),
            role(12, 112.0, "db-02", True), role(12, 112.0, "db-01", False),
        ]
        found = measure.promotion(samples, old_leader="db-01", since=100.5)
        self.assertEqual(found[0], "db-02")
        self.assertEqual(found[1], 112.0)

    def test_old_leader_staying_leader_is_not_promotion(self):
        samples = [role(1, 100.0, "db-01", True)]
        self.assertIsNone(measure.promotion(samples, old_leader="db-01", since=100.5))


class SplitBrainTest(unittest.TestCase):
    def test_two_leaders_in_same_tick_is_caught(self):
        samples = [role(5, 105.0, "db-01", True), role(5, 105.0, "db-02", True)]
        self.assertEqual(measure.max_simultaneous_leaders(samples), 2)

    def test_leaders_in_different_ticks_is_one(self):
        samples = [role(4, 104.0, "db-01", True), role(6, 106.0, "db-02", True)]
        self.assertEqual(measure.max_simultaneous_leaders(samples), 1)


class LossTest(unittest.TestCase):
    def _attempts(self):
        return [
            probe(1, 100.0, True),   # 장애 전 성공
            probe(2, 100.5, True),   # 장애 전 성공
            probe(3, 101.2, True),   # 장애 후 성공(비동기 유실 가능)
            probe(4, 101.7, False),  # 실패했지만 실제로는 커밋됨(모르게 커밋)
        ]

    def test_counts_lost_and_lost_before_and_unknown_commit(self):
        attempts = self._attempts()
        run_id = "H-4"
        present = {"H-4:1", "H-4:2", "H-4:4"}  # 3은 유실, 4는 실패인데 남음
        result = measure.loss(attempts, present, run_id, injected_at=101.0)
        self.assertEqual(result.acked, 3)
        self.assertEqual(result.lost, ("H-4:3",))
        self.assertEqual(result.lost_before, ())      # 유실된 3은 장애 후라 "장애 전 유실" 아님
        self.assertEqual(result.unknown_commits, ("H-4:4",))

    def test_lost_before_flags_data_loss_of_acknowledged_rows(self):
        attempts = self._attempts()
        present = {"H-4:1", "H-4:3"}  # 장애 전 성공한 2가 사라짐 → 치명적 유실
        result = measure.loss(attempts, present, "H-4", injected_at=101.0)
        self.assertIn("H-4:2", result.lost_before)


class CpuTest(unittest.TestCase):
    def test_percent_from_two_samples(self):
        earlier = CpuSample("db-01", 100.0, busy=0, total=0)
        later = CpuSample("db-01", 101.0, busy=90, total=100)
        self.assertAlmostEqual(measure.cpu_percent(earlier, later), 90.0)

    def test_no_elapsed_total_is_none(self):
        s = CpuSample("db-01", 100.0, busy=10, total=100)
        self.assertIsNone(measure.cpu_percent(s, s))


if __name__ == "__main__":
    unittest.main()
