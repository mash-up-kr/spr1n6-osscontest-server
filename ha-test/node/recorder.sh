#!/usr/bin/env bash
# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

#
# HA 시험 기록 도구. 관찰 노드(db-03)에서 DB OS 계정(opensql)으로 돈다. ha-test 가 올리고 부른다.
#
#   recorder.sh prepare <run_dir>            HA_DB_PASSWORD 로 VIP 접속 파일(.pgpass)을 만든다
#   recorder.sh start   <run_dir> <run_id>   쓰기 기록과 역할 기록을 백그라운드로 시작한다
#   recorder.sh stop    <run_dir>            두 기록을 멈추고, 진행 중인 시도가 기록될 때까지 기다린다
#   recorder.sh insert  <run_dir> <tag>      VIP 로 행 하나를 넣고 응답 시각과 id 를 출력한다
#   recorder.sh cleanup <run_dir>            기록을 멈추고 접속 파일을 지운다
#
# 쓰기 기록 probe.log : 0.5초마다 VIP 로 INSERT. seq, 시작 시각, 끝 시각, ok|fail, id|오류
# 역할 기록 roles.log : 1초마다 Patroni REST 3대. tick, 시각, 노드, /patroni 응답(실패면 ERR)
#
# 설정은 환경변수로 받는다.
#   HA_VIP HA_VIP_PORT HA_WRITE_POOL HA_DB_USER HA_PROBE_TABLE HA_PATRONI_PORT
#   HA_PATRONI_TARGETS="db-01=10.40.1.11 db-02=10.40.1.12 db-03=10.40.1.13"
set -u

# 기능테스트 절차의 기록 주기다.
PROBE_INTERVAL=0.5
ROLE_INTERVAL=1
# libpq 가 받는 가장 짧은 연결 제한이 2초다.
CONNECT_TIMEOUT=2
# 시도 하나의 상한. 연결 2초에 INSERT 를 더한 값이다. 이보다 오래 걸린 시도는 실패로 적는다.
ATTEMPT_TIMEOUT=5
# 역할 조회 한 번이 다음 조회(1초 뒤)와 겹치지 않게 1초보다 짧게 둔다.
REST_TIMEOUT=0.8

SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
TAB=$(printf '\t')

now() {
  date +%s.%N
}

one_line() {
  printf '%s' "$1" | tr '\t\n\r' '   ' | cut -c1-160
}

running() {
  [ -s "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null
}

require_env() {
  local name
  for name in "$@"; do
    if [ -z "${!name:-}" ]; then
      echo "error: 환경변수 $name 이 없다" >&2
      exit 2
    fi
  done
}

# OpenProxy 1.1.3 은 풀의 default_role 을 접속 때 적용하지 않는다. 앱과 같이 세션 첫 문장으로 역할을 정해야
# Primary 로 간다. 이 문장은 단독으로 보내야 한다.
psql_vip() {
  local run_dir=$1
  shift
  PGPASSFILE="$run_dir/.pgpass" PGCONNECT_TIMEOUT=$CONNECT_TIMEOUT timeout "$ATTEMPT_TIMEOUT" \
    psql "host=$HA_VIP port=$HA_VIP_PORT dbname=$HA_WRITE_POOL user=$HA_DB_USER" \
    -X -q -A -t -v ON_ERROR_STOP=1 -c "SET SERVER ROLE TO 'default'" "$@" 2>&1
}

prepare() {
  local run_dir=$1 escaped
  require_env HA_DB_PASSWORD HA_VIP HA_VIP_PORT HA_DB_USER
  mkdir -p "$run_dir"
  chmod 700 "$run_dir"
  # .pgpass 형식에서 \ 와 : 는 \ 로 감싼다.
  escaped=${HA_DB_PASSWORD//\\/\\\\}
  escaped=${escaped//:/\\:}
  (umask 077 && printf '%s:%s:*:%s:%s\n' "$HA_VIP" "$HA_VIP_PORT" "$HA_DB_USER" "$escaped" > "$run_dir/.pgpass")
  echo "prepared=$(now)"
}

probe_once() {
  local run_dir=$1 run_id=$2 seq=$3 started ended out rc row
  started=$(now)
  out=$(psql_vip "$run_dir" -c "INSERT INTO $HA_PROBE_TABLE (tag, ts) VALUES ('$run_id:$seq', clock_timestamp()) RETURNING id")
  rc=$?
  ended=$(now)
  row=$(printf '%s\n' "$out" | grep -E '^[0-9]+$' | tail -n 1)
  if [ "$rc" -eq 0 ] && [ -n "$row" ]; then
    printf '%s\t%s\t%s\tok\t%s\n' "$seq" "$started" "$ended" "$row" >> "$run_dir/probe.log"
  else
    printf '%s\t%s\t%s\tfail\trc=%s %s\n' "$seq" "$started" "$ended" "$rc" "$(one_line "$out")" >> "$run_dir/probe.log"
  fi
}

# 시도마다 백그라운드로 띄워, 한 시도가 연결을 기다리는 동안에도 0.5초 간격을 지킨다.
probe_loop() {
  local run_dir=$1 run_id=$2 seq=0 stopping=0
  trap 'stopping=1' TERM INT
  echo $$ > "$run_dir/probe.pid"
  while [ "$stopping" -eq 0 ]; do
    seq=$((seq + 1))
    probe_once "$run_dir" "$run_id" "$seq" &
    sleep "$PROBE_INTERVAL"
  done
  wait
  rm -f "$run_dir/probe.pid"
}

sample_role() {
  local run_dir=$1 tick=$2 node=$3 ip=$4 at body
  at=$(now)
  body=$(curl -s -m "$REST_TIMEOUT" "http://$ip:$HA_PATRONI_PORT/patroni" | tr -d '\t\n\r')
  printf '%s\t%s\t%s\t%s\n' "$tick" "$at" "$node" "${body:-ERR}" >> "$run_dir/roles.log"
}

role_loop() {
  local run_dir=$1 tick=0 stopping=0 target
  trap 'stopping=1' TERM INT
  echo $$ > "$run_dir/roles.pid"
  while [ "$stopping" -eq 0 ]; do
    tick=$((tick + 1))
    for target in $HA_PATRONI_TARGETS; do
      sample_role "$run_dir" "$tick" "${target%%=*}" "${target#*=}" &
    done
    sleep "$ROLE_INTERVAL"
  done
  wait
  rm -f "$run_dir/roles.pid"
}

spawn() {
  local run_dir=$1
  shift
  # SSH 세션이 끝나도 기록이 이어지게 새 세션으로 떼어 낸다.
  if command -v setsid >/dev/null 2>&1; then
    setsid nohup bash "$SELF" "$@" </dev/null >>"$run_dir/recorder.err" 2>&1 &
  else
    nohup bash "$SELF" "$@" </dev/null >>"$run_dir/recorder.err" 2>&1 &
  fi
}

start() {
  local run_dir=$1 run_id=$2 _
  require_env HA_VIP HA_VIP_PORT HA_WRITE_POOL HA_DB_USER HA_PROBE_TABLE HA_PATRONI_PORT HA_PATRONI_TARGETS
  if [ ! -f "$run_dir/.pgpass" ]; then
    echo "error: $run_dir/.pgpass 가 없다. prepare 를 먼저 한다" >&2
    exit 1
  fi
  if running "$run_dir/probe.pid" || running "$run_dir/roles.pid"; then
    echo "error: $run_dir 은 이미 기록 중이다" >&2
    exit 1
  fi
  rm -f "$run_dir/probe.pid" "$run_dir/roles.pid"
  : > "$run_dir/probe.log"
  : > "$run_dir/roles.log"
  spawn "$run_dir" _probe-loop "$run_dir" "$run_id"
  spawn "$run_dir" _role-loop "$run_dir"
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if [ -s "$run_dir/probe.pid" ] && [ -s "$run_dir/roles.pid" ]; then
      echo "started=$(now)"
      return 0
    fi
    sleep 0.2
  done
  echo "error: 기록이 시작되지 않았다: $(one_line "$(cat "$run_dir/recorder.err" 2>/dev/null)")" >&2
  exit 1
}

stop() {
  local run_dir=$1 pidfile _
  for pidfile in "$run_dir/probe.pid" "$run_dir/roles.pid"; do
    if running "$pidfile"; then
      kill -TERM "$(cat "$pidfile")" 2>/dev/null
    fi
  done
  # 진행 중인 시도(최대 ATTEMPT_TIMEOUT)가 끝나 기록될 때까지 기다린다.
  for _ in $(seq 1 40); do
    if ! running "$run_dir/probe.pid" && ! running "$run_dir/roles.pid"; then
      echo "stopped=$(now)"
      return 0
    fi
    sleep 0.25
  done
  echo "error: 기록이 10초 안에 멈추지 않았다" >&2
  return 1
}

insert() {
  local run_dir=$1 tag=$2 out rc acked row
  require_env HA_VIP HA_VIP_PORT HA_WRITE_POOL HA_DB_USER HA_PROBE_TABLE
  out=$(psql_vip "$run_dir" -F "$TAB" -c "INSERT INTO $HA_PROBE_TABLE (tag, ts) VALUES ('$tag', clock_timestamp()) RETURNING id, extract(epoch FROM clock_timestamp())")
  rc=$?
  acked=$(now)
  row=$(printf '%s\n' "$out" | grep -E "^[0-9]+$TAB" | tail -n 1)
  if [ "$rc" -ne 0 ] || [ -z "$row" ]; then
    echo "error: rc=$rc $(one_line "$out")" >&2
    return 1
  fi
  printf 'acked=%s\nrow=%s\n' "$acked" "$row"
}

cleanup() {
  local run_dir=$1 pidfile
  if ! stop "$run_dir" >/dev/null 2>&1; then
    # 멈추지 않은 기록은 프로세스 그룹째 끝낸다. setsid 로 떼어 내서 pid 가 곧 그룹 번호다.
    for pidfile in "$run_dir/probe.pid" "$run_dir/roles.pid"; do
      if running "$pidfile"; then
        kill -KILL -- "-$(cat "$pidfile")" 2>/dev/null || kill -KILL "$(cat "$pidfile")" 2>/dev/null
      fi
      rm -f "$pidfile"
    done
  fi
  rm -f "$run_dir/.pgpass"
  echo "cleaned=$(now)"
}

command=${1:-}
[ $# -gt 0 ] && shift
case "$command" in
  prepare) prepare "$@" ;;
  start) start "$@" ;;
  stop) stop "$@" ;;
  insert) insert "$@" ;;
  cleanup) cleanup "$@" ;;
  _probe-loop) probe_loop "$@" ;;
  _role-loop) role_loop "$@" ;;
  *)
    echo "usage: $0 {prepare|start|stop|insert|cleanup} <run_dir> [...]" >&2
    exit 2
    ;;
esac
