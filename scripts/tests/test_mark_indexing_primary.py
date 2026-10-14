"""Exercise the Patroni callback with fake psql/sleep; never connect to a DB."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


CALLBACK = Path(__file__).resolve().parents[1] / "ha" / "mark-indexing-primary.sh"


class PrimaryCallbackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.log = self.directory / "calls.jsonl"
        psql = self.directory / "psql"
        psql.write_text(
            f"#!{sys.executable}\n"
            "import json, os, pathlib, sys\n"
            "log = pathlib.Path(os.environ['CALLBACK_TEST_LOG'])\n"
            "previous = len(log.read_text().splitlines()) if log.exists() else 0\n"
            "with log.open('a') as output:\n"
            "    output.write(json.dumps(sys.argv[1:]) + '\\n')\n"
            "if any(key in os.environ for key in ('PGHOSTADDR', 'PGSERVICE', 'PGSERVICEFILE')): sys.exit(92)\n"
            "sys.exit(1 if previous < int(os.environ.get('CALLBACK_TEST_FAILURES', '0')) else 0)\n"
        )
        psql.chmod(0o755)
        sleep = self.directory / "sleep"
        sleep.write_text("#!/bin/sh\nexit 0\n")
        sleep.chmod(0o755)
        self.env = os.environ | {
            "PATH": f"{self.directory}:{os.environ['PATH']}",
            "CALLBACK_TEST_LOG": str(self.log),
            "INDEXING_HA_SOCKET_DIR": "/test/local-socket",
            "INDEXING_HA_PORT": "5432",
            "INDEXING_HA_DATABASE": "aidocs",
            "INDEXING_HA_USERNAME": "existing-ha-user",
        }

    def run_callback(self, action="on_role_change", role="primary"):
        return subprocess.run(
            ["/bin/sh", str(CALLBACK), action, role, "test-cluster"],
            env=self.env, capture_output=True, text=True, timeout=5,
        )

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_replica_and_unrelated_events_do_not_connect(self):
        for action, role in [("on_start", "replica"), ("on_role_change", "standby_leader"), ("on_stop", "primary")]:
            with self.subTest(action=action, role=role):
                self.assertEqual(0, self.run_callback(action, role).returncode)
        self.assertEqual([], self.calls())

    def test_start_restart_and_promotion_use_local_socket_and_callback_account(self):
        for action, role in [("on_start", "primary"), ("on_restart", "master"), ("on_role_change", "primary")]:
            with self.subTest(action=action, role=role):
                self.assertEqual(0, self.run_callback(action, role).returncode)
                args = self.calls()[-1]
                for option, value in [("-h", "/test/local-socket"), ("-p", "5432"), ("-U", "existing-ha-user"), ("-d", "aidocs")]:
                    self.assertEqual(value, args[args.index(option) + 1])
                command = args[args.index("-c") + 1]
                self.assertIn("DO $$ BEGIN", command)
                self.assertIn("pg_is_in_recovery()", command)
                self.assertIn("default_transaction_read_only", command)
                self.assertIn("CALL public.mark_indexing_recovery_primary()", command)
                self.assertIn("END $$;", command)

    def test_remote_address_is_rejected_before_connecting(self):
        for key, value in [("INDEXING_HA_SOCKET_DIR", "10.40.1.100"),
                           ("INDEXING_HA_SOCKET_DIR", "/test/socket,10.40.1.100"),
                           ("INDEXING_HA_DATABASE", "postgresql://10.40.1.100/aidocs"),
                           ("INDEXING_HA_PORT", "0")]:
            with self.subTest(key=key, value=value):
                previous = self.env[key]
                self.env[key] = value
                self.assertNotEqual(0, self.run_callback().returncode)
                self.env[key] = previous
        self.assertEqual([], self.calls())

    def test_inherited_libpq_routing_settings_are_removed(self):
        self.env.update(PGHOSTADDR="10.40.1.100", PGSERVICE="old-primary", PGSERVICEFILE="/test/service")
        self.assertEqual(0, self.run_callback().returncode)
        self.assertEqual(1, len(self.calls()))

    def test_transient_failure_retries_and_then_stops(self):
        self.env["CALLBACK_TEST_FAILURES"] = "2"
        self.assertEqual(0, self.run_callback().returncode)
        self.assertEqual(3, len(self.calls()))

    def test_persistent_failure_is_bounded_and_reported(self):
        self.env["CALLBACK_TEST_FAILURES"] = "100"
        result = self.run_callback()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(10, len(self.calls()))
        self.assertIn("recovery remains disabled", result.stderr)


if __name__ == "__main__":
    unittest.main()
