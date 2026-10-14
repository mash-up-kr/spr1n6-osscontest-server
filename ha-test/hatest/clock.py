"""시각과 표시 형식.

측정값은 서버 시계(chrony 로 3대가 맞춰져 있다)로 재고, 기다림과 시간 제한은 이 모듈의 시계로 잰다.
"""
from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone
from typing import Optional

KST = timezone(timedelta(hours=9), "KST")


class RealClock:
    def now(self) -> float:
        return time.time()

    def sleep(self, seconds: float) -> None:
        if seconds > 0:
            time.sleep(seconds)


class FakeClock:
    """모의 실행과 테스트용 시계. sleep 은 기다리지 않고 시각만 앞으로 옮긴다."""

    def __init__(self, start: float = 1_792_000_000.0):
        self._now = start

    def now(self) -> float:
        return self._now

    def sleep(self, seconds: float) -> None:
        if seconds > 0:
            self._now += seconds


def fmt_time(ts: Optional[float], date: bool = True) -> str:
    """에포크 초를 KST 로 보인다. 밀리초까지 적는다."""
    if ts is None:
        return "-"
    moment = datetime.fromtimestamp(ts, KST)
    head = moment.strftime("%Y-%m-%d %H:%M:%S" if date else "%H:%M:%S")
    return f"{head}.{moment.microsecond // 1000:03d} KST"


def fmt_seconds(value: Optional[float], digits: int = 1) -> str:
    if value is None:
        return "-"
    return f"{value:.{digits}f}초"


def fmt_bytes(value: Optional[int]) -> str:
    if value is None:
        return "-"
    if value < 1024:
        return f"{value} B"
    if value < 1024 * 1024:
        return f"{value / 1024:.1f} KB"
    return f"{value / 1024 / 1024:.1f} MB"


def stamp(ts: float) -> str:
    """결과 폴더와 실행 ID 에 쓰는 KST 시각 문자열."""
    return datetime.fromtimestamp(ts, KST).strftime("%Y%m%d-%H%M%S")
