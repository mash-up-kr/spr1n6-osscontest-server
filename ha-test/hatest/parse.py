"""원격 명령과 기록 도구의 출력을 값으로 바꾼다. 형식이 어긋난 줄은 조용히 넘기지 않고 ParseError 를 낸다."""
from __future__ import annotations

import json
from typing import Dict, List, Optional, Set, Tuple

from .model import (
    LEADER_ROLES,
    CpuSample,
    InsertAck,
    Member,
    ProbeAttempt,
    RecorderLogs,
    ReplicationRow,
    RoleSample,
)


class ParseError(ValueError):
    pass


def parse_key_values(text: str) -> Dict[str, str]:
    values: Dict[str, str] = {}
    for line in text.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def parse_member(node: str, observed_at: Optional[float], body: str) -> Member:
    """Patroni REST /patroni 응답 하나. 빈 응답·ERR·깨진 JSON 은 응답 없음으로 본다."""
    body = body.strip()
    if not body or body == "ERR":
        return Member(node=node, observed_at=observed_at, reachable=False)
    try:
        data = json.loads(body)
    except ValueError:
        return Member(node=node, observed_at=observed_at, reachable=False)
    role = data.get("role")
    xlog = data.get("xlog") or {}
    if role in LEADER_ROLES:
        state = data.get("state")
        wal = xlog.get("location")
    else:
        # Replica 의 state 는 Postgres 프로세스 상태(running)이고, 복제 상태는 replication_state 에 있다.
        state = data.get("replication_state") or data.get("state")
        wal = xlog.get("replayed_location", xlog.get("received_location"))
    timeline = data.get("timeline")
    return Member(
        node=node,
        observed_at=observed_at,
        reachable=True,
        role=role,
        state=state,
        timeline=int(timeline) if timeline is not None else None,
        wal_position=int(wal) if wal is not None else None,
    )


def parse_patroni_scan(text: str) -> Dict[str, Member]:
    """노드  시각  응답 세 칸 줄."""
    members: Dict[str, Member] = {}
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split("\t", 2)
        if len(parts) != 3:
            raise ParseError(f"Patroni 조회 {number}번째 줄 형식이 다르다: {line[:80]}")
        node, at, body = parts
        members[node] = parse_member(node, _float(at, number, "시각"), body)
    return members


def parse_role_log(text: str) -> List[RoleSample]:
    """tick  시각  노드  응답 네 칸 줄."""
    samples: List[RoleSample] = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split("\t", 3)
        if len(parts) != 4:
            raise ParseError(f"역할 기록 {number}번째 줄 형식이 다르다: {line[:80]}")
        tick, at, node, body = parts
        samples.append(RoleSample(tick=_int(tick, number, "tick"), member=parse_member(node, _float(at, number, "시각"), body)))
    return samples


def parse_probe_log(text: str) -> List[ProbeAttempt]:
    """seq  시작  끝  ok|fail  id|오류 다섯 칸 줄."""
    attempts: List[ProbeAttempt] = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split("\t", 4)
        if len(parts) < 4 or parts[3] not in ("ok", "fail"):
            raise ParseError(f"쓰기 기록 {number}번째 줄 형식이 다르다: {line[:80]}")
        attempts.append(
            ProbeAttempt(
                seq=_int(parts[0], number, "seq"),
                start=_float(parts[1], number, "시작"),
                end=_float(parts[2], number, "끝"),
                ok=parts[3] == "ok",
                detail=parts[4] if len(parts) == 5 else "",
            )
        )
    return attempts


def parse_recorder_logs(probe_text: str, roles_text: str) -> RecorderLogs:
    return RecorderLogs(
        probe_text=probe_text,
        roles_text=roles_text,
        attempts=parse_probe_log(probe_text),
        roles=parse_role_log(roles_text),
    )


def parse_replication(text: str) -> List[ReplicationRow]:
    rows: List[ReplicationRow] = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 5:
            raise ParseError(f"pg_stat_replication {number}번째 줄 형식이 다르다: {line[:80]}")
        lag = _int(parts[4], number, "lag")
        rows.append(
            ReplicationRow(
                application_name=parts[0],
                client_addr=parts[1],
                state=parts[2],
                sync_state=parts[3],
                replay_lag_bytes=lag if lag >= 0 else None,
            )
        )
    return rows


def parse_cpu(node: str, text: str) -> CpuSample:
    """ts=시각 줄과 /proc/stat 첫 줄(cpu user nice system idle iowait irq softirq steal ...)."""
    at: Optional[float] = None
    fields: Optional[List[int]] = None
    for line in text.splitlines():
        if line.startswith("ts="):
            at = float(line[3:])
        elif line.startswith("cpu "):
            fields = [int(value) for value in line.split()[1:]]
    if at is None or fields is None or len(fields) < 4:
        raise ParseError(f"{node} CPU 출력 형식이 다르다: {text[:80]}")
    # guest·guest_nice 는 user·nice 에 이미 들어 있어 합계에서 뺀다.
    counted = fields[:8]
    idle = counted[3] + (counted[4] if len(counted) > 4 else 0)
    total = sum(counted)
    return CpuSample(node=node, at=at, busy=total - idle, total=total)


def parse_activity(text: str) -> Set[Tuple[int, str]]:
    """pg_stat_activity 감시 출력. (pid, query_start) 쌍 하나가 쿼리 실행 한 번이다."""
    seen: Set[Tuple[int, str]] = set()
    for line in text.splitlines():
        parts = line.strip().split("\t")
        if len(parts) == 2 and parts[0].isdigit():
            seen.add((int(parts[0]), parts[1]))
    return seen


def parse_insert(tag: str, text: str) -> InsertAck:
    """기록 도구 insert 출력: acked=시각, row=id<TAB>Primary 시각."""
    values = parse_key_values(text)
    try:
        row_id, primary_at = values["row"].split("\t")
        return InsertAck(tag=tag, row_id=int(row_id), primary_at=float(primary_at), acked_at=float(values["acked"]))
    except (KeyError, ValueError):
        raise ParseError(f"INSERT 응답 형식이 다르다: {text[:120]}") from None


def parse_marker_watch(text: str) -> Tuple[Optional[float], Optional[float], Optional[int]]:
    """H-1 Replica 감시의 NOTICE 출력. (감시 시작 시각, 행이 보인 시각, 보인 행 id)."""
    ready = visible = None
    row_id = None
    for line in text.splitlines():
        words = line.replace("NOTICE:", " ").split()
        if len(words) >= 2 and words[0] == "ready":
            ready = float(words[1])
        elif len(words) >= 3 and words[0] == "visible":
            visible = float(words[1])
            row_id = int(words[2])
    return ready, visible, row_id


def parse_vip_watch(text: str) -> Tuple[Optional[float], Optional[float]]:
    """VIP 감시 출력. os=시각, aws=시각(옮겨지지 않았으면 timeout)."""
    values = parse_key_values(text)

    def at(key: str) -> Optional[float]:
        value = values.get(key)
        try:
            return float(value) if value else None
        except ValueError:
            return None

    return at("os"), at("aws")


def _float(value: str, number: int, name: str) -> float:
    try:
        return float(value)
    except ValueError:
        raise ParseError(f"{number}번째 줄의 {name} 값이 숫자가 아니다: {value!r}") from None


def _int(value: str, number: int, name: str) -> int:
    try:
        return int(value)
    except ValueError:
        raise ParseError(f"{number}번째 줄의 {name} 값이 정수가 아니다: {value!r}") from None
