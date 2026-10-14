"""화면 출력. 진행 단계를 시각과 함께 찍고, 같은 내용을 events.log 로 남긴다."""
from __future__ import annotations

import sys
import unicodedata
from typing import List, Optional, Sequence, TextIO

from .clock import fmt_time

RULE = "─" * 72


def display_width(text: str) -> int:
    """터미널에서 차지하는 칸 수. 한글은 두 칸이다."""
    return sum(2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in text)


def pad(text: str, width: int) -> str:
    return text + " " * max(width - display_width(text), 0)


def table(headers: Sequence[str], rows: Sequence[Sequence[str]]) -> str:
    widths = [display_width(h) for h in headers]
    for row in rows:
        widths = [max(width, display_width(cell)) for width, cell in zip(widths, row)]
    lines = ["  ".join(pad(cell, width) for cell, width in zip(headers, widths)).rstrip()]
    lines += ["  ".join(pad(cell, width) for cell, width in zip(row, widths)).rstrip() for row in rows]
    return "\n".join(lines)


class Console:
    def __init__(self, clock, stream: Optional[TextIO] = None):
        self.clock = clock
        self.stream = stream or sys.stdout
        self.events: List[str] = []

    def section(self, title: str) -> None:
        self._print(f"\n{RULE}\n {title}\n{RULE}")

    def step(self, title: str, detail: str = "") -> None:
        line = f"[{fmt_time(self.clock.now(), date=False)}] {title}" + (f" · {detail}" if detail else "")
        self.events.append(line)
        self._print(line)

    def info(self, text: str) -> None:
        self.events.append("    " + text)
        self._print("    " + text)

    def warn(self, text: str) -> None:
        self.events.append("!!  " + text)
        self._print("!!  " + text)

    def block(self, text: str) -> None:
        self._print(text)

    def drain_events(self) -> str:
        text = "\n".join(self.events)
        self.events = []
        return text

    def _print(self, text: str) -> None:
        print(text, file=self.stream, flush=True)
