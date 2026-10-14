"""노드에서 실행할 bash 스크립트를 만든다.

스크립트는 SSH 표준입력으로 보낸다(remote.Remote). 그래서 셸 따옴표를 겹쳐 쓸 일이 없다.
틀 안의 {{이름}} 자리에 값을 넣는다. 넣는 값은 셋 중 하나다: config 검증(config._validate)을 거친 설정값,
이 도구가 만든 run_id·tag(영문·숫자·-·:), 그리고 shlex.quote 로 감싼 값(인스턴스 ID·잠금 주인·기록 도구 인자).
"""
from __future__ import annotations

import shlex

from .config import Config

# 이름 순서로 마지막에 읽히는 drop-in 이라 다른 drop-in 의 Restart= 를 덮는다. /run 아래라 재부팅하면 사라진다.
PATRONI_DROP_IN_DIR = "/run/systemd/system/patroni.service.d"
PATRONI_DROP_IN = f"{PATRONI_DROP_IN_DIR}/zz-ha-test.conf"

RECORDER_EOF = "HA_TEST_RECORDER_EOF"


def fill(template: str, **values: object) -> str:
    script = template
    for key, value in values.items():
        script = script.replace("{{" + key + "}}", str(value))
    if "{{" in script:
        raise ValueError(f"채우지 않은 자리가 남았다: {script[script.index('{{'):][:40]}")
    return script.strip("\n") + "\n"


def _psql(config: Config) -> str:
    """노드 안에서 소켓으로 붙는 슈퍼유저 psql. DB OS 계정(opensql) 셸에서 부른다."""
    return (
        f"psql -X -q -A -t -F \"$(printf '\\t')\" -h {config.pg_socket_dir} "
        f"-U postgres -d {config.database} -v ON_ERROR_STOP=1"
    )


def node_facts(config: Config) -> str:
    return fill(
        r"""
echo "ts=$(date +%s.%N)"
if ip -4 -o addr show | grep -qF ' {{VIP}}/'; then echo 'vip=1'; else echo 'vip=0'; fi
echo "patroni_service=$(systemctl is-active patroni)"
echo "patroni_enabled=$(systemctl is-enabled patroni 2>/dev/null)"
echo "openproxy_service=$(systemctl is-active openproxy)"
if [ -r /sys/class/watchdog/watchdog0/state ]; then
  echo "watchdog=$(cat /sys/class/watchdog/watchdog0/state)"
else
  echo 'watchdog=none'
fi
echo "boot_id=$(cat /proc/sys/kernel/random/boot_id)"
""",
        VIP=config.vip,
    )


def patroni_scan(config: Config) -> str:
    """세 노드의 Patroni REST /patroni 를 동시에 읽는다. 줄마다 노드, 시각, 응답(실패면 ERR)."""
    targets = " ".join(f"{node.name}={node.ip}" for node in config.nodes)
    return fill(
        r"""
for target in {{TARGETS}}; do
  (
    at=$(date +%s.%N)
    body=$(curl -s -m 1 "http://${target#*=}:{{PORT}}/patroni" | tr -d '\t\n\r')
    printf '%s\t%s\t%s\n' "${target%%=*}" "$at" "${body:-ERR}"
  ) &
done
wait
""",
        TARGETS=targets,
        PORT=config.patroni_port,
    )


# 인스턴스 메타데이터(IMDS)의 ENI 사설 IP 목록은 AWS 가 이 ENI 에 붙였다고 보는 IP 다. 서버 eth0 와 따로 본다.
# AWS API(DescribeNetworkInterfaces)를 쓰지 않는 이유: VIP 용 IAM 역할에는 할당·해제 권한만 있다(terraform/openproxy_vip.tf).
IMDS_BASE = "http://169.254.169.254/latest/meta-data/network/interfaces/macs"
IMDS_TOKEN = "curl -s -m 2 -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 300'"


def aws_vip_held(config: Config) -> str:
    """이 노드의 ENI 에 AWS 기준으로 VIP 가 붙어 있으면 aws_vip=1, 아니면 0, 메타데이터를 못 읽으면 error."""
    return fill(
        r"""
base={{BASE}}
token=$({{TOKEN}})
if [ -z "$token" ]; then echo 'aws_vip=error'; exit 0; fi
held=0
for mac in $(curl -s -m 2 -H "X-aws-ec2-metadata-token: $token" "$base/"); do
  if curl -s -m 2 -H "X-aws-ec2-metadata-token: $token" "$base/${mac%/}/local-ipv4s" | grep -qx '{{VIP}}'; then held=1; fi
done
echo "aws_vip=$held"
""",
        BASE=IMDS_BASE,
        TOKEN=IMDS_TOKEN,
        VIP=config.vip,
    )


def boot_id() -> str:
    return "cat /proc/sys/kernel/random/boot_id\n"


def ssh_ok() -> str:
    return "echo ok\n"


def kill_db_stack() -> str:
    """H-3 장애 주입. systemd 가 Patroni 를 다시 띄우지 못하게 한 뒤 Patroni 와 PostgreSQL 을 함께 SIGKILL 한다.

    disable 은 watchdog 재부팅 뒤에도 죽은 채로 두기 위해서다. 되살리기는 revive_db_stack 이 한다.
    """
    return fill(
        r"""
set -e
sudo mkdir -p {{DROP_IN_DIR}}
printf '[Service]\nRestart=no\n' | sudo tee {{DROP_IN}} >/dev/null
sudo systemctl daemon-reload
sudo systemctl disable patroni 2>/dev/null
echo "boot_id=$(cat /proc/sys/kernel/random/boot_id)"
echo "ts=$(date +%s.%N)"
sudo systemctl kill -s SIGKILL patroni
""",
        DROP_IN_DIR=PATRONI_DROP_IN_DIR,
        DROP_IN=PATRONI_DROP_IN,
    )


def revive_db_stack() -> str:
    return fill(
        r"""
set -e
sudo rm -f {{DROP_IN}}
sudo systemctl daemon-reload
sudo systemctl enable patroni 2>/dev/null
echo "ts=$(date +%s.%N)"
sudo systemctl start patroni
""",
        DROP_IN=PATRONI_DROP_IN,
    )


def stop_service(unit: str) -> str:
    return fill(
        r"""
set -e
echo "ts=$(date +%s.%N)"
sudo systemctl stop {{UNIT}}
""",
        UNIT=unit,
    )


def start_service(unit: str) -> str:
    return fill(
        r"""
set -e
echo "ts=$(date +%s.%N)"
sudo systemctl start {{UNIT}}
""",
        UNIT=unit,
    )


def switchover(config: Config, leader_member: str, candidate_member: str) -> str:
    """DB OS 계정으로 실행한다."""
    return fill(
        r"""
echo "ts=$(date +%s.%N)"
patronictl -c {{CONFIG}} switchover --leader {{LEADER}} --candidate {{CANDIDATE}} --force
""",
        CONFIG=config.patroni_config,
        LEADER=leader_member,
        CANDIDATE=candidate_member,
    )


def replication(config: Config) -> str:
    return fill(
        r"""
{{PSQL}} <<'SQL'
SELECT application_name, coalesce(host(client_addr), ''), state, sync_state,
       coalesce(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn), -1)::bigint
FROM pg_stat_replication
ORDER BY application_name;
SQL
""",
        PSQL=_psql(config),
    )


def probe_tags(config: Config, run_id: str) -> str:
    return fill(
        r"""
{{PSQL}} <<'SQL'
SELECT tag FROM {{TABLE}} WHERE starts_with(tag, '{{RUN_ID}}:');
SQL
""",
        PSQL=_psql(config),
        TABLE=config.probe_table,
        RUN_ID=run_id,
    )


def row_count(config: Config, table: str, row_id: int) -> str:
    return fill(
        r"""
{{PSQL}} <<'SQL'
SELECT count(*) FROM {{TABLE}} WHERE id = {{ID}};
SQL
""",
        PSQL=_psql(config),
        TABLE=table,
        ID=int(row_id),
    )


def cpu_sample() -> str:
    return 'echo "ts=$(date +%s.%N)"\nhead -n 1 /proc/stat\n'


def activity_watch(config: Config, since: float, seconds: int) -> str:
    """H-2. seconds 동안 0.2초마다 이 노드의 pg_stat_activity 에서 검색 쿼리를 찾는다.

    검색 쿼리는 SearchChunkRepository 의 SQL 조각(벡터 거리, 키워드 tsquery, 문맥 LATERAL, ef_search)으로 가린다.
    """
    return fill(
        r"""
echo "ready=$(date +%s.%N)"
timeout {{SECONDS}} {{PSQL}} <<'SQL'
SELECT pid, extract(epoch FROM query_start)
FROM pg_stat_activity
WHERE pid <> pg_backend_pid()
  AND datname = '{{DATABASE}}'
  AND query_start >= to_timestamp({{SINCE}})
  AND (query ILIKE '%embedding <=>%'
       OR query ILIKE '%to_tsquery(''simple''%'
       OR query ILIKE '%ctx_before%'
       OR query ILIKE '%hnsw.ef_search%')
\watch 0.2
SQL
echo "done=$(date +%s.%N)"
""",
        SECONDS=int(seconds),
        PSQL=_psql(config),
        DATABASE=config.database,
        SINCE=f"{since:.3f}",
    )


def marker_watch(config: Config, tag: str, seconds: int) -> str:
    """H-1. Replica 에서 표식 행이 보일 때까지 5ms 마다 찾는다. 한 세션 안에서 돌아 접속 시간이 측정에 섞이지 않는다."""
    return fill(
        r"""
psql -X -q -h {{SOCKET}} -U postgres -d {{DATABASE}} -v ON_ERROR_STOP=1 <<'SQL'
DO $$
DECLARE
  deadline timestamptz := clock_timestamp() + interval '{{SECONDS}} seconds';
  found_id bigint;
BEGIN
  RAISE NOTICE 'ready %', extract(epoch FROM clock_timestamp());
  LOOP
    SELECT id INTO found_id FROM {{TABLE}} WHERE tag = '{{TAG}}';
    IF found_id IS NOT NULL THEN
      RAISE NOTICE 'visible % %', extract(epoch FROM clock_timestamp()), found_id;
      RETURN;
    END IF;
    IF clock_timestamp() > deadline THEN
      RAISE NOTICE 'timeout %', extract(epoch FROM clock_timestamp());
      RETURN;
    END IF;
    PERFORM pg_sleep(0.005);
  END LOOP;
END
$$;
SQL
""",
        SOCKET=config.pg_socket_dir,
        DATABASE=config.database,
        SECONDS=int(seconds),
        TABLE=config.probe_table,
        TAG=tag,
    )


def vip_watch(config: Config, seconds: int, check_aws: bool) -> str:
    """이 노드가 VIP 를 갖게 되는 순간을 서버(eth0)는 0.05초, AWS(IMDS 의 ENI 사설 IP)는 0.2초마다 확인한다."""
    return fill(
        r"""
vip={{VIP}}
until=$(( $(date +%s) + {{SECONDS}} ))
echo "ready=$(date +%s.%N)"
if [ "{{CHECK_AWS}}" = 1 ]; then
  (
    base={{BASE}}
    token=$({{TOKEN}})
    while [ "$(date +%s)" -lt "$until" ]; do
      for mac in $(curl -s -m 1 -H "X-aws-ec2-metadata-token: $token" "$base/"); do
        if curl -s -m 1 -H "X-aws-ec2-metadata-token: $token" "$base/${mac%/}/local-ipv4s" | grep -qx "$vip"; then
          echo "aws=$(date +%s.%N)"
          exit 0
        fi
      done
      sleep 0.2
    done
    echo "aws=timeout"
  ) &
fi
os=timeout
while [ "$(date +%s)" -lt "$until" ]; do
  if ip -4 -o addr show | grep -qF " $vip/"; then os=$(date +%s.%N); break; fi
  sleep 0.05
done
echo "os=$os"
wait
""",
        VIP=config.vip,
        SECONDS=int(seconds),
        CHECK_AWS=1 if check_aws else 0,
        BASE=IMDS_BASE,
        TOKEN=IMDS_TOKEN,
    )


def recorder_deploy(config: Config, content: str) -> str:
    if RECORDER_EOF in content:
        raise ValueError("기록 도구 내용에 heredoc 끝 표시가 들어 있다")
    script = fill(
        r"""
mkdir -p {{DIR}}/runs
chmod 700 {{DIR}}
cat > {{DIR}}/recorder.sh <<'{{EOF}}'
@@CONTENT@@
{{EOF}}
chmod 700 {{DIR}}/recorder.sh
echo "sha256=$(sha256sum {{DIR}}/recorder.sh | cut -d' ' -f1)"
""",
        DIR=config.remote_dir,
        EOF=RECORDER_EOF,
    )
    # 기록 도구 본문은 fill 뒤에 넣는다. 본문 안의 문자열을 자리 표시로 오해하지 않게 하기 위해서다.
    return script.replace("@@CONTENT@@", content.rstrip("\n"))


def recorder_call(config: Config, *args: str) -> str:
    """DB OS 계정으로 기록 도구를 부른다. 설정은 환경변수로 넘긴다."""
    targets = " ".join(f"{node.name}={node.ip}" for node in config.nodes)
    return fill(
        r"""
export HA_VIP={{VIP}} HA_VIP_PORT={{PORT}} HA_WRITE_POOL={{POOL}} HA_DB_USER={{USER}}
export HA_PROBE_TABLE={{TABLE}} HA_PATRONI_PORT={{PATRONI_PORT}} HA_PATRONI_TARGETS={{TARGETS}}
bash {{DIR}}/recorder.sh {{ARGS}}
""",
        VIP=config.vip,
        PORT=config.vip_port,
        POOL=config.write_pool,
        USER=config.db_user,
        TABLE=config.probe_table,
        PATRONI_PORT=config.patroni_port,
        TARGETS=shlex.quote(targets),
        DIR=config.remote_dir,
        ARGS=" ".join(shlex.quote(arg) for arg in args),
    )


def recorder_read(config: Config, run_id: str, name: str) -> str:
    return fill("cat {{DIR}}/runs/{{RUN}}/{{NAME}}", DIR=config.remote_dir, RUN=run_id, NAME=name)


def recorder_tail(config: Config, run_id: str, lines: int) -> str:
    """진행 표시용으로 쓰기 기록 끝부분만 읽는다."""
    return fill("tail -n {{N}} {{DIR}}/runs/{{RUN}}/probe.log", N=int(lines), DIR=config.remote_dir, RUN=run_id)


def vip_held(config: Config) -> str:
    """이 노드 eth0 에 VIP 가 붙어 있으면 1. 진행 표시용 가벼운 확인이다."""
    return fill(
        r"""
if ip -4 -o addr show | grep -qF ' {{VIP}}/'; then echo 'vip=1'; else echo 'vip=0'; fi
""",
        VIP=config.vip,
    )


def lock(config: Config, owner: str) -> str:
    return fill(
        r"""
mkdir -p {{DIR}}
if mkdir {{DIR}}/lock 2>/dev/null; then
  printf '%s\n' {{OWNER}} > {{DIR}}/lock/owner
  echo "locked=1"
else
  echo "locked=0"
  echo "owner=$(cat {{DIR}}/lock/owner 2>/dev/null)"
fi
""",
        DIR=config.remote_dir,
        OWNER=shlex.quote(owner),
    )


def unlock(config: Config) -> str:
    return fill("rm -rf {{DIR}}/lock", DIR=config.remote_dir)
