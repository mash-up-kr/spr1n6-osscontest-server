"""서버·기록 도구 출력 파싱. 형식이 어긋난 줄은 ParseError 로 드러내고 조용히 넘기지 않는다."""
import json
import unittest

from hatest import parse
from hatest.parse import ParseError


def patroni_leader(tl=53, wal=200):
    return json.dumps({"role": "primary", "state": "running", "timeline": tl, "xlog": {"location": wal}})


def patroni_replica(tl=53, replayed=150, state="streaming"):
    return json.dumps(
        {"role": "replica", "state": "running", "replication_state": state, "timeline": tl, "xlog": {"replayed_location": replayed}}
    )


class MemberParseTest(unittest.TestCase):
    def test_leader_reads_role_state_and_wal_location(self):
        member = parse.parse_member("db-01", 1000.0, patroni_leader(tl=53, wal=500))
        self.assertTrue(member.is_leader)
        self.assertEqual(member.state, "running")
        self.assertEqual(member.timeline, 53)
        self.assertEqual(member.wal_position, 500)

    def test_replica_reads_replication_state_and_replayed_location(self):
        member = parse.parse_member("db-02", 1000.0, patroni_replica(replayed=480, state="streaming"))
        self.assertTrue(member.is_replica)
        self.assertEqual(member.state, "streaming")
        self.assertEqual(member.wal_position, 480)

    def test_empty_or_err_body_is_unreachable(self):
        # 재부팅·종료된 노드는 빈 응답이나 ERR 로 온다. 응답 없음으로 봐야 Leader 수 계산이 맞다.
        self.assertFalse(parse.parse_member("db-01", 1.0, "").reachable)
        self.assertFalse(parse.parse_member("db-01", 1.0, "ERR").reachable)
        self.assertFalse(parse.parse_member("db-01", 1.0, "<html>502").reachable)


class ScanParseTest(unittest.TestCase):
    def test_three_node_scan(self):
        text = f"db-01\t1000.0\t{patroni_leader()}\ndb-02\t1000.1\t{patroni_replica()}\ndb-03\t1000.2\tERR"
        members = parse.parse_patroni_scan(text)
        self.assertEqual(sorted(members), ["db-01", "db-02", "db-03"])
        self.assertTrue(members["db-01"].is_leader)
        self.assertFalse(members["db-03"].reachable)

    def test_wrong_column_count_raises(self):
        with self.assertRaises(ParseError):
            parse.parse_patroni_scan("db-01 1000.0 {}")


class ProbeLogParseTest(unittest.TestCase):
    def test_ok_and_fail_rows(self):
        text = "1\t100.0\t100.02\tok\t5001\n2\t100.5\t101.5\tfail\trc=1 25006 recovery"
        attempts = parse.parse_probe_log(text)
        self.assertEqual(len(attempts), 2)
        self.assertTrue(attempts[0].ok)
        self.assertEqual(attempts[0].detail, "5001")
        self.assertFalse(attempts[1].ok)

    def test_bad_status_raises(self):
        with self.assertRaises(ParseError):
            parse.parse_probe_log("1\t100\t100\tmaybe\tx")


class RoleLogParseTest(unittest.TestCase):
    def test_tick_groups_three_nodes(self):
        text = (
            f"1\t100.0\tdb-01\t{patroni_leader()}\n"
            f"1\t100.0\tdb-02\t{patroni_replica()}\n"
            f"1\t100.0\tdb-03\tERR"
        )
        samples = parse.parse_role_log(text)
        self.assertEqual({s.tick for s in samples}, {1})
        self.assertEqual(len(samples), 3)


class CpuParseTest(unittest.TestCase):
    def test_reads_ts_and_proc_stat(self):
        sample = parse.parse_cpu("db-01", "ts=100.0\ncpu  1000 10 200 8000 50 0 5 0 0 0")
        self.assertEqual(sample.at, 100.0)
        # idle(8000) + iowait(50) 를 뺀 나머지가 busy 다.
        self.assertEqual(sample.total, 1000 + 10 + 200 + 8000 + 50 + 0 + 5 + 0)
        self.assertEqual(sample.busy, sample.total - (8000 + 50))

    def test_missing_cpu_line_raises(self):
        with self.assertRaises(ParseError):
            parse.parse_cpu("db-01", "ts=100.0\n")


class ReplicationParseTest(unittest.TestCase):
    def test_two_streaming_rows(self):
        text = "postgresql2\t10.40.1.12\tstreaming\tasync\t0\npostgresql3\t10.40.1.13\tstreaming\tasync\t128"
        rows = parse.parse_replication(text)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0].state, "streaming")
        self.assertEqual(rows[1].replay_lag_bytes, 128)


class InsertParseTest(unittest.TestCase):
    def test_reads_row_id_and_times(self):
        ack = parse.parse_insert("H-1:marker", "acked=1000.5\nrow=5001\t1000.4")
        self.assertEqual(ack.row_id, 5001)
        self.assertEqual(ack.primary_at, 1000.4)
        self.assertEqual(ack.acked_at, 1000.5)

    def test_malformed_raises(self):
        with self.assertRaises(ParseError):
            parse.parse_insert("t", "nope")


class VipWatchParseTest(unittest.TestCase):
    def test_os_and_aws(self):
        os_at, aws_at = parse.parse_vip_watch("ready=99.0\nos=100.5\naws=101.5")
        self.assertEqual(os_at, 100.5)
        self.assertEqual(aws_at, 101.5)

    def test_timeout_is_none(self):
        os_at, aws_at = parse.parse_vip_watch("ready=99.0\nos=timeout\naws=timeout")
        self.assertIsNone(os_at)
        self.assertIsNone(aws_at)


if __name__ == "__main__":
    unittest.main()
