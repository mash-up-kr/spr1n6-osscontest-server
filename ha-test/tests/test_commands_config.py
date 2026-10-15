# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""원격 명령 조립과 설정 읽기."""
import unittest

from hatest import commands
from hatest.config import ConfigError, load_config
from hatest.remote import Remote


def config():
    return load_config()


class FillTest(unittest.TestCase):
    def test_leftover_placeholder_raises(self):
        with self.assertRaises(ValueError):
            commands.fill("echo {{A}} {{B}}", A="x")

    def test_fills_values(self):
        self.assertEqual(commands.fill("x={{V}}", V=3).strip(), "x=3")


class CommandContentTest(unittest.TestCase):
    def setUp(self):
        self.cfg = config()

    def test_patroni_scan_targets_each_node_ip(self):
        script = commands.patroni_scan(self.cfg)
        for node in self.cfg.nodes:
            self.assertIn(f"{node.name}={node.ip}", script)
        self.assertIn(f":{self.cfg.patroni_port}/patroni", script)

    def test_kill_db_stack_disables_autostart_then_sigkill(self):
        script = commands.kill_db_stack()
        self.assertIn("systemctl disable patroni", script)
        self.assertIn("Restart=no", script)
        self.assertIn("kill -s SIGKILL patroni", script)
        # 자동 시작을 끈 뒤에 죽여야 watchdog 재부팅 후에도 죽은 채로 남는다.
        self.assertLess(script.index("disable patroni"), script.index("SIGKILL patroni"))

    def test_revive_reenables_autostart(self):
        self.assertIn("systemctl enable patroni", commands.revive_db_stack())

    def test_recorder_call_passes_config_as_env(self):
        script = commands.recorder_call(self.cfg, "start", "/d", "run")
        self.assertIn(f"HA_VIP={self.cfg.vip}", script)
        self.assertIn("recorder.sh", script)


class AwsVipOwnerTest(unittest.TestCase):
    """AWS 기준 VIP 주인은 OpenProxy 노드마다 메타데이터(aws_vip=1/0/error)를 읽어 정한다."""

    def _owner(self, outputs):
        from hatest.cluster import Cluster
        from hatest.clock import FakeClock
        from hatest.remote import RemoteResult

        class FakeRemote:
            def run(self, node, script, **kwargs):
                rc, stdout = outputs[node]
                return RemoteResult(node, rc, stdout, "")

        return Cluster(config(), FakeRemote(), FakeClock()).aws_vip_owner()

    def test_owner_is_the_node_whose_eni_has_vip(self):
        self.assertEqual(self._owner({"db-02": (0, "aws_vip=1\n"), "db-03": (0, "aws_vip=0\n")}), ("db-02", None))

    def test_metadata_failure_is_reported_not_treated_as_absent(self):
        # 메타데이터를 못 읽은 것을 "VIP 없음"으로 바꾸면 조용히 틀린다. 이유를 돌려줘야 한다.
        owner, error = self._owner({"db-02": (0, "aws_vip=error\n"), "db-03": (255, "")})
        self.assertIsNone(owner)
        self.assertIn("db-02", error)
        self.assertIn("db-03", error)

    def test_no_eni_has_vip(self):
        owner, error = self._owner({"db-02": (0, "aws_vip=0"), "db-03": (0, "aws_vip=0")})
        self.assertIsNone(owner)
        self.assertIn("어느 ENI 에도", error)

    def test_aws_check_uses_metadata_not_aws_api(self):
        # VIP 용 IAM 역할에는 Describe 권한이 없다(terraform/openproxy_vip.tf). API 를 부르면 항상 실패한다.
        cfg = config()
        for script in (commands.aws_vip_held(cfg), commands.vip_watch(cfg, 30, True)):
            self.assertNotIn("aws ec2", script)
            self.assertIn("169.254.169.254", script)


class RemoteStdinTest(unittest.TestCase):
    def test_script_wrapped_in_main_and_fed_from_devnull(self):
        # 스크립트 안의 명령이 표준입력에서 나머지 스크립트를 삼키지 않게 main() 으로 감싸고 </dev/null 로 부른다.
        remote = Remote(config())
        wrapped = remote._stdin("echo hi\n", {"SECRET": "p@ss word"})
        self.assertIn("main() {", wrapped)
        self.assertIn("main </dev/null", wrapped)
        self.assertIn("export SECRET='p@ss word'", wrapped)


class ConfigTest(unittest.TestCase):
    def test_loads_three_nodes(self):
        cfg = config()
        self.assertEqual(len(cfg.nodes), 3)
        self.assertEqual(cfg.replicas, tuple(n for n in cfg.node_names if n != cfg.primary))

    def test_env_override_wins(self):
        cfg = load_config(environ={"HA_VIP": "10.9.9.9"})
        self.assertEqual(cfg.vip, "10.9.9.9")

    def test_observer_cannot_be_primary(self):
        with self.assertRaises(ConfigError):
            load_config(environ={"HA_OBSERVER": load_config().primary})

    def test_quoted_value_rejected(self):
        with self.assertRaises(ConfigError):
            load_config(environ={"HA_VIP": "10.0.0.1; rm -rf /"})


if __name__ == "__main__":
    unittest.main()
