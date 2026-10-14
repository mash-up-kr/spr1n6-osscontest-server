"""기록에서 측정값을 계산한다. 서버 쪽 시각만 쓰고, 입력이 같으면 결과도 같다."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from typing import Dict, Iterable, Optional, Sequence, Set, Tuple

from .model import CpuSample, ProbeAttempt, RoleSample


@dataclass(frozen=True)
class WriteStop:
    """쓰기가 멈춘 구간. 다시 성공하지 못했으면 duration 과 resumed_at 이 None 이다."""

    duration: Optional[float]
    last_ok_at: Optional[float]
    first_fail_at: Optional[float]
    resumed_at: Optional[float]
    failures: int
    attempts: int


def write_stop(attempts: Sequence[ProbeAttempt], since: float, until: Optional[float] = None) -> WriteStop:
    """since 뒤에 처음 실패한 시도를 기준으로, 그 전 마지막 성공 응답부터 그 뒤 첫 성공 응답까지를 잰다.

    실패가 없으면 since 를 사이에 둔 성공 응답 사이 가장 긴 간격을 쓴다(기록 주기 0.5초 언저리).
    until 을 주면 그 시각 전에 시작한 시도만 실패로 본다. 한 기록에 장애가 두 번 있을 때 나눠 재기 위해서다.
    """
    window = [a for a in attempts if a.start >= since and (until is None or a.start < until)]
    successes = sorted((a for a in attempts if a.ok), key=lambda a: a.end)
    failures = sorted((a for a in window if not a.ok), key=lambda a: a.start)
    if not failures:
        acks = [a.end for a in successes if a.end >= since and (until is None or a.end < until)]
        before = [a.end for a in successes if a.end < since]
        if before:
            acks.insert(0, before[-1])
        gaps = [later - earlier for earlier, later in zip(acks, acks[1:])]
        return WriteStop(
            duration=max(gaps) if gaps else None,
            last_ok_at=None,
            first_fail_at=None,
            resumed_at=acks[-1] if acks else None,
            failures=0,
            attempts=len(window),
        )
    first_fail = failures[0]
    last_ok = max((a.end for a in successes if a.end <= first_fail.start), default=None)
    resumed = min((a.end for a in successes if a.start >= first_fail.start), default=None)
    counted = [a for a in failures if resumed is None or a.start < resumed]
    origin = last_ok if last_ok is not None else since
    return WriteStop(
        duration=resumed - origin if resumed is not None else None,
        last_ok_at=last_ok,
        first_fail_at=first_fail.start,
        resumed_at=resumed,
        failures=len(counted),
        attempts=len(window),
    )


def promotion(samples: Sequence[RoleSample], old_leader: str, since: float) -> Optional[Tuple[str, float]]:
    """since 뒤에 old_leader 가 아닌 노드가 Leader 로 처음 보인 (노드, 시각)."""
    candidates = [s.member for s in samples if s.member.is_leader and s.member.node != old_leader]
    candidates = [m for m in candidates if m.observed_at is not None and m.observed_at >= since]
    if not candidates:
        return None
    first = min(candidates, key=lambda m: m.observed_at)
    return first.node, first.observed_at


def max_simultaneous_leaders(samples: Iterable[RoleSample]) -> int:
    """같은 tick(같은 순간 3대 조회) 안에서 Leader 라고 답한 노드 수의 최댓값."""
    leaders_by_tick: Dict[int, Set[str]] = defaultdict(set)
    for sample in samples:
        if sample.member.is_leader:
            leaders_by_tick[sample.tick].add(sample.member.node)
    return max((len(nodes) for nodes in leaders_by_tick.values()), default=0)


@dataclass(frozen=True)
class Loss:
    acked: int
    present: int
    lost: Tuple[str, ...]
    lost_before: Tuple[str, ...]
    unknown_commits: Tuple[str, ...]


def loss(attempts: Sequence[ProbeAttempt], present_tags: Set[str], run_id: str, injected_at: float) -> Loss:
    """성공 응답을 받은 행과 새 Primary 에 남은 행을 비교한다.

    lost 는 응답을 받았는데 없는 행, lost_before 는 그중 장애 주입 전에 응답을 받은 행,
    unknown_commits 는 실패로 기록됐는데 실제로는 커밋된 행이다.
    """
    tags = {a.seq: a.tag(run_id) for a in attempts}
    acked = {tags[a.seq] for a in attempts if a.ok}
    acked_before = {tags[a.seq] for a in attempts if a.ok and a.end < injected_at}
    probe_rows = {tag for tag in present_tags if tag in tags.values()}
    return Loss(
        acked=len(acked),
        present=len(probe_rows),
        lost=tuple(sorted(acked - probe_rows, key=_seq_order)),
        lost_before=tuple(sorted(acked_before - probe_rows, key=_seq_order)),
        unknown_commits=tuple(sorted(probe_rows - acked, key=_seq_order)),
    )


def _seq_order(tag: str) -> int:
    tail = tag.rsplit(":", 1)[-1]
    return int(tail) if tail.isdigit() else -1


def cpu_percent(earlier: CpuSample, later: CpuSample) -> Optional[float]:
    total = later.total - earlier.total
    if total <= 0:
        return None
    return (later.busy - earlier.busy) * 100.0 / total


def cpu_window(start: Dict[str, CpuSample], end: Dict[str, CpuSample]) -> Dict[str, Optional[float]]:
    return {node: cpu_percent(start[node], end[node]) for node in start if node in end}


def seconds_between(earlier: Optional[float], later: Optional[float]) -> Optional[float]:
    if earlier is None or later is None:
        return None
    return later - earlier
