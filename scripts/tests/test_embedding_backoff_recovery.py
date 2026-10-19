"""단일 장애 주입과 임시 Worker 설정을 DB·Docker·실제 임베딩 호출 없이 검증한다."""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
import io
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch


DIRECTORY = Path(__file__).resolve().parents[1] / "embedding-outage"
spec = importlib.util.spec_from_file_location("embedding_stub", DIRECTORY / "embedding-stub.py")
stub = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stub)
scenario = types.ModuleType("embedding_backoff_recovery")
scenario.__file__ = str(DIRECTORY / "embedding-backoff-recovery.sh")
sys.modules[scenario.__name__] = scenario
source = Path(scenario.__file__).read_text().split("<<'PYTHON'\n", 1)[1].rsplit("\nPYTHON", 1)[0]
exec(compile(source, scenario.__file__, "exec"), scenario.__dict__)


class SingleFailureTest(unittest.TestCase):
    def test_each_mode_fails_once_then_returns_fake_vectors(self):
        for mode, status in [("429", 429), ("5xx", 503)]:
            with self.subTest(mode=mode):
                stats = stub.Stats()
                handler_class = stub.make_handler(mode, stub.OutageWindow(90, fail_requests=1), 10, stats, 10)
                replies = []
                for _ in range(3):
                    handler = handler_class.__new__(handler_class)
                    payload = json.dumps({"input": ["first chunk", "second chunk"]}).encode()
                    handler.path = "/v1/embeddings"
                    handler.headers = {"Content-Length": str(len(payload))}
                    handler.rfile = io.BytesIO(payload)
                    handler._send = replies.append
                    handler._log = lambda *args: None
                    handler.do_POST()
                self.assertEqual([status, 200, 200], [r.status for r in replies])
                if mode == "429":
                    self.assertEqual("10", replies[0].headers["Retry-After"])
                data = json.loads(replies[1].body)["data"]
                self.assertEqual(2, len(data))
                self.assertEqual([1536, 1536], [len(item["embedding"]) for item in data])
                self.assertEqual({str(status): 1, "200": 2}, stats.snapshot())

    def test_concurrent_requests_consume_only_one_failure(self):
        window = stub.OutageWindow(90, fail_requests=1)
        with ThreadPoolExecutor(max_workers=8) as executor:
            failures = list(executor.map(window.fail_request, [100.0] * 40))
        self.assertEqual(1, sum(failures))

    def test_standalone_timed_outage_keeps_existing_behavior(self):
        window = stub.OutageWindow(90)
        self.assertTrue(window.fail_request(100))
        self.assertTrue(window.fail_request(189))
        self.assertFalse(window.fail_request(190))
        self.assertEqual("30", stub.plan_reply("429", True, 1, 10).headers["Retry-After"])

    def test_one_retry_passes_and_repeated_failures_fail(self):
        for mode, delay, code in [("429", 10, "RATE_LIMITED"), ("5xx", 8, "EMBEDDING_ERROR")]:
            final = {"status": "COMPLETED", "attempt_count": 2, "outcome": "INDEXED"}
            retries = [scenario.RetryObservation(1, delay, code)]
            arguments = (mode, scenario.base_delay_for(mode), 10 if mode == "429" else None)
            checks = scenario.evaluate(*arguments, retries, final, ["CLOSED"], 0)
            self.assertEqual(0, scenario.exit_code(checks))
            bad = scenario.evaluate(*arguments, retries * 2, final, ["CLOSED"], 0)
            self.assertEqual(1, scenario.exit_code(bad))
            final["attempt_count"] = 3
            bad = scenario.evaluate(*arguments, retries, final, ["CLOSED"], 0)
            self.assertEqual(1, scenario.exit_code(bad))

    def test_retry_deadlines(self):
        self.assertEqual((10, 10), scenario.backoff_bounds(scenario.base_delay_for("429"), 1, 10))
        self.assertEqual((8, 12), scenario.backoff_bounds(scenario.base_delay_for("5xx"), 1))


class WorkerLifecycleTest(unittest.TestCase):
    def run_scenario(self, fail=False, check_only=False):
        original_env = ["OPENAI_API_KEY=test-secret", "INDEXING_RETRY_BASE_DELAY=PT30S",
                        "INDEXING_QUEUE_POLL_INTERVAL=PT30S", "INDEXING_MAX_ATTEMPTS=5"]
        calls, environments, modes = [], [], []

        def docker(*args, **kwargs):
            calls.append(args)
            if args[0] == "inspect":
                return json.dumps({"State": {"Running": True}, "Image": "sha256:test-worker",
                                   "Config": {"Image": "worker:latest", "Env": original_env},
                                   "NetworkSettings": {"Networks": {"test-network": {}}}})
            if args[0] == "run":
                env_file = Path(args[args.index("--env-file") + 1])
                environments.append(dict(line.split("=", 1) for line in env_file.read_text().splitlines()))
            return ""

        def mode(*args):
            modes.append(args[2])
            if fail:
                raise scenario.StopTest("simulated failure")
            return 0

        argv = ["--worker1", "worker-1", "--worker2", "worker-2"]
        if check_only:
            argv.append("--check-only")
        with patch.object(scenario, "preflight", return_value=None), \
                patch.object(scenario, "wait_worker_ready"), \
                patch.object(scenario, "run_mode", side_effect=mode), \
                patch.object(scenario, "say"):
            code = scenario.main(argv, runner=docker, db=object())
        return code, calls, environments, modes

    def test_both_modes_override_only_temporary_workers_and_restore_originals(self):
        code, calls, environments, modes = self.run_scenario()
        self.assertEqual(0, code)
        self.assertEqual(["429", "5xx"], modes)
        self.assertEqual(["PT8S", "PT10S"], [e["INDEXING_RETRY_BASE_DELAY"] for e in environments])
        for env in environments:
            self.assertEqual("PT1S", env["INDEXING_QUEUE_POLL_INTERVAL"])
            self.assertEqual("2", env["INDEXING_MAX_ATTEMPTS"])
            self.assertEqual("test-secret", env["OPENAI_API_KEY"])
        self.assertEqual([("start", "worker-1"), ("start", "worker-2")],
                         [c for c in calls if c[0] == "start"])

    def test_failure_restores_original_workers(self):
        code, calls, _, _ = self.run_scenario(fail=True)
        self.assertEqual(1, code)
        self.assertIn(("start", "worker-1"), calls)
        self.assertIn(("start", "worker-2"), calls)

    def test_check_only_does_not_stop_or_start_workers(self):
        code, calls, environments, modes = self.run_scenario(check_only=True)
        self.assertEqual(0, code)
        self.assertFalse(any(c[0] in ("stop", "start", "run", "rm") for c in calls))
        self.assertEqual([], environments)
        self.assertEqual([], modes)


if __name__ == "__main__":
    unittest.main()
