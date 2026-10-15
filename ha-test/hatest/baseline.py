# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""기준 상태 검사. 시험 전과 원복 뒤에 같은 항목을 본다."""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Tuple

from .clock import fmt_bytes
from .config import Config
from .model import Snapshot

# 기준 상태의 "Lag 0" 은 patronictl 의 Lag in MB 단위다. 1MB 미만이면 0 으로 보인다.
# maximum_lag_on_failover(1MB)와도 같은 값이라, 이보다 뒤처진 Replica 는 승격 후보에서도 빠진다.
LAG_LIMIT_BYTES = 1024 * 1024


@dataclass(frozen=True)
class CheckItem:
    label: str
    expected: str
    actual: str
    ok: bool


@dataclass(frozen=True)
class Checklist:
    items: Tuple[CheckItem, ...]

    @property
    def ok(self) -> bool:
        return all(item.ok for item in self.items)

    def failures(self) -> List[CheckItem]:
        return [item for item in self.items if not item.ok]

    def summary(self) -> str:
        if self.ok:
            return "OK  " + " · ".join(f"{item.label} {item.actual}" for item in self.items if item.label in SUMMARY_LABELS)
        return "다름  " + " · ".join(f"{item.label}: 기대 {item.expected}, 실제 {item.actual}" for item in self.failures())


SUMMARY_LABELS = ("Leader", "Replica", "VIP", "watchdog")


def evaluate(snapshot: Snapshot, config: Config) -> Checklist:
    items: List[CheckItem] = []

    unreachable = [name for name in config.node_names if not snapshot.facts[name].reachable]
    items.append(CheckItem("SSH", "3대 접속", "접속 안 됨 " + ",".join(unreachable) if unreachable else "3대 접속", not unreachable))

    leaders = snapshot.leaders()
    items.append(CheckItem("Leader", config.primary, ",".join(leaders) or "없음", leaders == [config.primary]))

    replica_states = []
    replicas_ok = True
    for name in config.replicas:
        member = snapshot.members[name]
        lag = snapshot.lag_bytes(name)
        ok = member.is_replica and member.state == "streaming" and lag is not None and lag < LAG_LIMIT_BYTES
        replicas_ok = replicas_ok and ok
        replica_states.append(f"{name} {member.display_state} Lag {fmt_bytes(lag)}")
    items.append(CheckItem("Replica", "2대 streaming, Lag 1MB 미만", ", ".join(replica_states), replicas_ok))

    holders = snapshot.os_vip_holders()
    vip_actual = ",".join(holders) or "없음"
    vip_ok = holders == [config.vip_owner]
    if snapshot.aws_vip_checked:
        vip_actual += f" (AWS {snapshot.aws_vip_owner or snapshot.aws_vip_error or '없음'})"
        vip_ok = vip_ok and snapshot.aws_vip_owner == config.vip_owner
    items.append(CheckItem("VIP", f"{config.vip_owner} (서버 eth0·AWS ENI)", vip_actual, vip_ok))

    proxies = [f"{name} {snapshot.facts[name].openproxy_service or '-'}" for name in config.proxy_nodes]
    proxies_ok = all(snapshot.facts[name].openproxy_service == "active" for name in config.proxy_nodes)
    items.append(CheckItem("OpenProxy", "2대 active", ", ".join(proxies), proxies_ok))

    # watchdog 은 Patroni 가 Leader 일 때만 켠다. Replica 의 inactive 는 정상이다.
    watchdog = snapshot.facts[config.primary].watchdog or "-"
    items.append(CheckItem("watchdog", f"{config.primary} active", f"{config.primary} {watchdog}", watchdog == "active"))

    # H-3 이 끈 자동 시작이 되돌아왔는지 본다. 꺼진 채로 두면 다음 재부팅 때 그 노드가 돌아오지 않는다.
    services = []
    services_ok = True
    for name in config.node_names:
        facts = snapshot.facts[name]
        ok = facts.patroni_service == "active" and facts.patroni_enabled == "enabled"
        services_ok = services_ok and ok
        if not ok:
            services.append(f"{name} {facts.patroni_service or '-'}·{facts.patroni_enabled or '-'}")
    items.append(CheckItem("Patroni 서비스", "3대 active·enabled", ", ".join(services) or "3대 active·enabled", services_ok))

    return Checklist(tuple(items))
