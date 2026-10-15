# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""시험 결과 형식. 모든 TC 가 같은 항목(TC · 기준 상태 · 장애 주입 시각 · 측정값 · 성공 기준 · 판정 · 원복 확인)을 같은 순서로 낸다."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

from .baseline import Checklist
from .clock import fmt_time
from .console import display_width, pad

# 기능명세서 DB HA 항목의 소분류와 시험항목이다.
TEST_CASES: Dict[str, Tuple[str, str]] = {
    "H-1": ("DB 데이터 복제", "Primary에 저장된 데이터가 Replica DB에 복제되어 동일하게 조회되는지 확인"),
    "H-2": ("읽기 요청 분산", "조회 요청 시 Read 요청이 Replica DB로 전달되어 처리되는지 확인"),
    "H-3": ("DB 자동 장애 복구", "Primary DB 중단 시 Replica DB가 새로운 Primary로 자동 승격되어 DB 서비스를 계속 제공하는지 확인"),
    "H-4": ("DB 장애 복구 후 데이터 유지", "Primary 장애 및 Failover 이후 장애 전에 저장한 데이터를 정상적으로 조회할 수 있는지 확인"),
    "H-5": ("DB 연결 경로 자동 전환", "Active OpenProxy 중단 시 Standby OpenProxy가 VIP를 인수하고 동일 DB Endpoint로 연결 가능한지 확인"),
    "H-6": ("노드별 상태 조회", "DB 노드별 역할(primary/Replica)과 복제 상태를 조회할 수 있음"),
}

PASS, FAIL, NOT_TESTED = "Pass", "Fail", "NT"
WIDTH = 76
LABEL_WIDTH = 11


@dataclass(frozen=True)
class Criterion:
    text: str
    ok: bool
    actual: str


@dataclass
class Outcome:
    """시나리오가 TC 하나에 대해 잰 것."""

    injected_at: Optional[float]
    injection: str
    measurements: List[Tuple[str, str]]
    criteria: List[Criterion]
    notes: List[str] = field(default_factory=list)


@dataclass
class TcResult:
    tc: str
    verdict: str
    started_at: float
    baseline: Optional[Checklist]
    outcome: Optional[Outcome]
    restore: Optional[Checklist]
    reason: Optional[str] = None
    evidence: Optional[Path] = None
    simulated: bool = False

    @property
    def title(self) -> str:
        return TEST_CASES[self.tc][0]

    @property
    def item(self) -> str:
        return TEST_CASES[self.tc][1]


def decide(outcome: Optional[Outcome], aborted: Optional[str]) -> str:
    """성공 기준이 모두 맞으면 Pass, 하나라도 어긋나면 Fail. 끝까지 재지 못했으면 NT(Not Test)."""
    if aborted or outcome is None or not outcome.criteria:
        return NOT_TESTED
    return PASS if all(c.ok for c in outcome.criteria) else FAIL


def render_text(result: TcResult) -> str:
    head = f" {result.tc} · {result.title}"
    tail = f"{result.verdict} "
    lines = ["═" * WIDTH, head + " " * max(WIDTH - display_width(head) - display_width(tail), 1) + tail, "═" * WIDTH]
    if result.simulated:
        lines.append(" 모의 실행이다. 아래 값은 실제 측정값이 아니다.")
    for label, values in _rows(result):
        for index, value in enumerate(values):
            lines.append(" " + pad(label if index == 0 else "", LABEL_WIDTH) + value)
    lines.append("─" * WIDTH)
    return "\n".join(lines)


def _rows(result: TcResult) -> List[Tuple[str, List[str]]]:
    outcome = result.outcome
    rows: List[Tuple[str, List[str]]] = [("시험항목", [result.item])]
    rows.append(("기준 상태", [result.baseline.summary() if result.baseline else "-"]))
    if outcome is not None:
        rows.append(("장애 주입", [f"{fmt_time(outcome.injected_at)} · {outcome.injection}"]))
        rows.append(("측정값", [f"{label} {value}" for label, value in outcome.measurements] or ["-"]))
        rows.append(("성공 기준", [f"{'✔' if c.ok else '✘'} {c.text} — {c.actual}" for c in outcome.criteria] or ["-"]))
    rows.append(("판정", [result.verdict + (f" ({result.reason})" if result.reason else "")]))
    rows.append(("원복 확인", [result.restore.summary() if result.restore else "-"]))
    if result.evidence:
        rows.append(("증빙", [str(result.evidence)]))
    if outcome is not None and outcome.notes:
        rows.append(("참고", list(outcome.notes)))
    return rows


def render_markdown(result: TcResult) -> str:
    """노션에 붙일 수 있게 표와 한 단계 목록만 쓴다."""
    lines = [f"## {result.tc} · {result.title}", ""]
    if result.simulated:
        lines += ["모의 실행이다. 아래 값은 실제 측정값이 아니다.", ""]
    lines += ["| 항목 | 내용 |", "| --- | --- |"]
    for label, values in _rows(result):
        if label == "성공 기준":
            for index, value in enumerate(values, 1):
                lines.append(f"| 성공 기준 {index} | {_cell(value)} |")
        elif label != "참고":
            lines.append(f"| {label} | {_cell(' · '.join(values))} |")
    if result.outcome is not None and result.outcome.notes:
        lines += ["", "참고", ""] + [f"- {note}" for note in result.outcome.notes]
    if result.baseline is not None and not result.baseline.ok:
        lines += ["", "기준 상태와 다른 항목", ""] + [
            f"- {item.label}: 기대 {item.expected}, 실제 {item.actual}" for item in result.baseline.failures()
        ]
    return "\n".join(lines) + "\n"


def _cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def to_json(result: TcResult) -> dict:
    outcome = result.outcome
    return {
        "tc": result.tc,
        "title": result.title,
        "verdict": result.verdict,
        "reason": result.reason,
        "simulated": result.simulated,
        "started_at": result.started_at,
        "baseline_ok": result.baseline.ok if result.baseline else None,
        "restore_ok": result.restore.ok if result.restore else None,
        "injected_at": outcome.injected_at if outcome else None,
        "injection": outcome.injection if outcome else None,
        "measurements": dict(outcome.measurements) if outcome else {},
        "criteria": [{"text": c.text, "ok": c.ok, "actual": c.actual} for c in outcome.criteria] if outcome else [],
        "notes": outcome.notes if outcome else [],
    }


def write(results: Sequence[TcResult], directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for result in results:
        (directory / f"{result.tc}.md").write_text(render_markdown(result), encoding="utf-8")
    (directory / "report.txt").write_text("\n\n".join(render_text(r) for r in results) + "\n", encoding="utf-8")
    (directory / "result.json").write_text(
        json.dumps([to_json(r) for r in results], ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def render_summary(results: Sequence[TcResult]) -> str:
    lines = ["결과 요약", "", "| # | 소분류 | Result | 비고 |", "| --- | --- | --- | --- |"]
    for result in sorted(results, key=lambda r: r.tc):
        note = result.reason or ""
        if result.outcome is not None and result.verdict == FAIL:
            note = ", ".join(c.text for c in result.outcome.criteria if not c.ok)
        lines.append(f"| {result.tc} | {result.title} | {result.verdict} | {_cell(note)} |")
    return "\n".join(lines) + "\n"
