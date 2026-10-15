# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""시험 대상 클러스터 설정을 읽는다.

값은 환경변수(HA_ 접두사), 저장소 루트 .env 의 HA_ 키, ha-test/cluster.conf 순서로 찾는다.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Mapping, Optional, Tuple

HA_ROOT = Path(__file__).resolve().parent.parent
REPO_ROOT = HA_ROOT.parent
CONF_PATH = HA_ROOT / "cluster.conf"
ENV_PATH = REPO_ROOT / ".env"


class ConfigError(Exception):
    """설정이 빠졌거나 어긋나 시험을 시작할 수 없다."""


@dataclass(frozen=True)
class Node:
    name: str
    ssh: str
    ip: str
    member: str


@dataclass(frozen=True)
class Config:
    nodes: Tuple[Node, ...]
    primary: str
    vip_owner: str
    standby_proxy: str
    observer: str
    h6_stop_node: str
    vip: str
    vip_port: int
    write_pool: str
    database: str
    probe_table: str
    db_os_user: str
    patroni_port: int
    patroni_config: str
    pg_socket_dir: str
    remote_dir: str
    aws_vip_check: bool
    app_url: str
    app_user_id: str
    search_queries: Tuple[str, ...]
    db_user: str
    db_password: Optional[str]
    grafana_url: str
    grafana_dashboard_uid: str
    grafana_user: str
    grafana_password: str

    @property
    def node_names(self) -> Tuple[str, ...]:
        return tuple(node.name for node in self.nodes)

    @property
    def replicas(self) -> Tuple[str, ...]:
        return tuple(name for name in self.node_names if name != self.primary)

    @property
    def proxy_nodes(self) -> Tuple[str, ...]:
        return (self.vip_owner, self.standby_proxy)

    def node(self, name: str) -> Node:
        for node in self.nodes:
            if node.name == name:
                return node
        raise KeyError(name)

    def require_db_password(self) -> str:
        """VIP 로 쓰는 시험(H-1·H-3·H-4·H-5)에 필요한 DB 비밀번호. 없으면 시작을 막는다."""
        if not self.db_password:
            raise ConfigError("DB 비밀번호가 없다. 저장소 .env 의 DB_PASSWORD 나 HA_DB_PASSWORD 를 채운다")
        return self.db_password


def read_key_values(path: Path) -> Dict[str, str]:
    """KEY=VALUE 줄을 읽는다. 빈 줄과 # 주석은 건너뛰고 값의 앞뒤 따옴표를 뗀다."""
    values: Dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def load_config(
    environ: Optional[Mapping[str, str]] = None,
    conf_path: Path = CONF_PATH,
    env_path: Path = ENV_PATH,
) -> Config:
    environ = os.environ if environ is None else environ
    if not conf_path.exists():
        raise ConfigError(f"{conf_path} 가 없다")
    conf = read_key_values(conf_path)
    dotenv = read_key_values(env_path) if env_path.exists() else {}

    def get(key: str) -> str:
        for source in (environ, dotenv):
            if f"HA_{key}" in source:
                return source[f"HA_{key}"]
        if key in conf:
            return conf[key]
        raise ConfigError(f"cluster.conf 에 {key} 가 없다")

    def get_int(key: str) -> int:
        value = get(key)
        try:
            return int(value)
        except ValueError:
            raise ConfigError(f"{key} 는 정수여야 한다: {value}") from None

    nodes = []
    for name in get("NODES").split():
        prefix = name.upper().replace("-", "")
        nodes.append(Node(name=name, ssh=get(f"{prefix}_SSH"), ip=get(f"{prefix}_IP"), member=get(f"{prefix}_MEMBER")))

    # DB 계정은 앱과 같은 .env 키를 쓴다. 시험만 다른 계정을 쓰려면 HA_ 키로 덮는다.
    db_user = environ.get("HA_DB_USER") or dotenv.get("HA_DB_USER") or dotenv.get("DB_USERNAME") or get("DB_USER")
    db_password = environ.get("HA_DB_PASSWORD") or dotenv.get("HA_DB_PASSWORD") or dotenv.get("DB_PASSWORD") or None

    config = Config(
        nodes=tuple(nodes),
        primary=get("PRIMARY"),
        vip_owner=get("VIP_OWNER"),
        standby_proxy=get("STANDBY_PROXY"),
        observer=get("OBSERVER"),
        h6_stop_node=get("H6_STOP_NODE"),
        vip=get("VIP"),
        vip_port=get_int("VIP_PORT"),
        write_pool=get("WRITE_POOL"),
        database=get("DATABASE"),
        probe_table=get("PROBE_TABLE"),
        db_os_user=get("DB_OS_USER"),
        patroni_port=get_int("PATRONI_PORT"),
        patroni_config=get("PATRONI_CONFIG"),
        pg_socket_dir=get("PG_SOCKET_DIR"),
        remote_dir=get("REMOTE_DIR"),
        aws_vip_check=get("AWS_VIP_CHECK").lower() in ("1", "true", "yes"),
        app_url=get("APP_URL").rstrip("/"),
        app_user_id=get("APP_USER_ID"),
        search_queries=tuple(q.strip() for q in get("SEARCH_QUERIES").split("|") if q.strip()),
        db_user=db_user,
        db_password=db_password,
        grafana_url=get("GRAFANA_URL").rstrip("/"),
        grafana_dashboard_uid=get("GRAFANA_DASHBOARD_UID"),
        grafana_user=get("GRAFANA_USER"),
        # 모니터링 compose 가 쓰는 키와 같다. 없으면 compose 기본값 admin 이다.
        grafana_password=environ.get("HA_GRAFANA_PASSWORD") or dotenv.get("GRAFANA_ADMIN_PASSWORD") or "admin",
    )
    _validate(config)
    return config


def _validate(config: Config) -> None:
    names = config.node_names
    if len(names) != 3:
        raise ConfigError(f"노드는 3대여야 한다: {' '.join(names)}")
    for key, value in (
        ("PRIMARY", config.primary),
        ("VIP_OWNER", config.vip_owner),
        ("STANDBY_PROXY", config.standby_proxy),
        ("OBSERVER", config.observer),
        ("H6_STOP_NODE", config.h6_stop_node),
    ):
        if value not in names:
            raise ConfigError(f"{key}={value} 가 NODES 에 없다")
    if config.vip_owner == config.standby_proxy:
        raise ConfigError("VIP_OWNER 와 STANDBY_PROXY 가 같다")
    # 관찰 노드에서 기록 도구가 돈다. H-3 에서 Primary 노드가 꺼지면 기록도 끊긴다.
    if config.observer == config.primary:
        raise ConfigError("OBSERVER 는 PRIMARY 와 달라야 한다")
    if config.h6_stop_node == config.primary:
        raise ConfigError("H6_STOP_NODE 는 Replica 여야 한다")
    # 아래 값은 노드에서 도는 bash 스크립트에 따옴표 없이 들어간다(commands.fill). 셸이 따로 해석할 글자를 막는다.
    script_values = [
        config.vip, config.write_pool, config.database, config.probe_table, config.db_user, config.db_os_user,
        config.patroni_config, config.pg_socket_dir, config.remote_dir,
    ]
    script_values += [value for node in config.nodes for value in (node.name, node.ip, node.member)]
    for value in script_values:
        if not value or any(ch in value for ch in " '\"\\$`;|&<>(){}*?!#~\n"):
            raise ConfigError(f"셸에 그대로 들어가는 값에 공백·따옴표·특수 문자를 쓰지 않는다: {value!r}")
