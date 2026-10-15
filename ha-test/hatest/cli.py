# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""ha-test 명령. status / check / restore / unlock / H-1~H-6 / all 과 --yes · --simulate 를 받는다."""
from __future__ import annotations

import argparse
import sys
from typing import List, Optional

from . import report, status
from .appclient import AppClient, SimAppClient
from .baseline import evaluate
from .clock import FakeClock, RealClock
from .cluster import Cluster
from .config import Config, ConfigError, load_config
from .console import Console
from .grafana import GrafanaMarks, SimMarks
from .remote import Remote
from .runner import TestRunner, manual_restore_hint, restore_cluster
from .scenarios import SCENARIOS
from .simulated import SimScript, SimulatedCluster

TC_NAMES = sorted(SCENARIOS)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ha-test", description="DB HA 기능테스트(H-1~H-6) 실행 도구")
    parser.add_argument("command", help="check | status | restore | unlock | " + " | ".join(TC_NAMES) + " | all")
    parser.add_argument("--yes", action="store_true", help="장애 주입 전 확인을 건너뛴다")
    parser.add_argument("--simulate", action="store_true", help="모의 클러스터로 끝까지 돌려 본다(서버 없이)")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        config = load_config()
    except ConfigError as error:
        print(f"설정 오류: {error}", file=sys.stderr)
        return 2

    clock = FakeClock() if args.simulate else RealClock()
    console = Console(clock)

    if args.simulate:
        return _run_simulated(args, config, console, clock)
    return _run_real(args, config, console, clock)


def _targets(command: str) -> List[str]:
    if command == "all":
        return TC_NAMES
    return [command]


def _confirm(console: Console, auto_yes: bool):
    def ask(prompt: str) -> bool:
        if auto_yes:
            console.info(f"{prompt} → --yes 로 진행")
            return True
        try:
            answer = input(f"{prompt} [y/N] ").strip().lower()
        except EOFError:
            return False
        return answer in ("y", "yes")

    return ask


def _run_real(args, config: Config, console: Console, clock) -> int:
    remote = Remote(config)
    cluster = Cluster(config, remote, clock)
    try:
        if args.command == "status":
            console.block(status.render(cluster.snapshot(), config))
            return 0
        if args.command == "check":
            checklist = evaluate(cluster.snapshot(), config)
            console.block(checklist.summary())
            return 0 if checklist.ok else 1
        if args.command == "unlock":
            # 시험이 강제로 끊겨 finally 가 돌지 못하면 관찰 노드에 잠금이 남는다. 다른 사람이 시험 중이 아닐 때만 쓴다.
            cluster.unlock()
            console.block("시험 잠금을 풀었다")
            return 0
        if args.command == "restore":
            restore_cluster(config, cluster, console, clock)
            checklist = evaluate(cluster.snapshot(), config)
            console.block(checklist.summary())
            if not checklist.ok:
                for line in manual_restore_hint(config):
                    console.info(line)
            return 0 if checklist.ok else 1
        if args.command not in SCENARIOS and args.command != "all":
            console.block(f"모르는 명령: {args.command}")
            return 2

        runner = TestRunner(
            config, cluster, console, clock,
            confirm=_confirm(console, args.yes),
            app_factory=lambda: AppClient(config),
            marks=GrafanaMarks(config, console),
        )
        results = [runner.run(tc) for tc in _targets(args.command)]
        if len(results) > 1:
            console.block("")
            console.block(report.render_summary(results))
        return 0 if all(r.verdict == report.PASS for r in results) else 1
    finally:
        cluster.close()


def _run_simulated(args, config: Config, console: Console, clock: FakeClock) -> int:
    if args.command not in SCENARIOS and args.command != "all":
        console.block(f"--simulate 는 시험 항목(H-1~H-6, all)에만 쓴다: {args.command}")
        return 2
    console.section("모의 클러스터 실행 (--simulate)")
    console.info("서버 없이 각본대로 돈다. 측정값은 각본 값이지 실제 값이 아니다.")

    results = []
    for tc in _targets(args.command):
        clock.sleep(5)  # TC 마다 시작 시각을 벌려 run_id 가 겹치지 않게 한다.
        cluster = SimulatedCluster(config, clock, SimScript())
        runner = TestRunner(
            config, cluster, console, clock,
            confirm=lambda prompt: True,
            app_factory=lambda cluster=cluster: SimAppClient(config, cluster),
            simulated=True,
            marks=SimMarks(config, console),
        )
        results.append(runner.run(tc))
    console.block("")
    console.block(report.render_summary(results))
    return 0
