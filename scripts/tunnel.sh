#!/usr/bin/env bash
#
# dev DB(EC2) SSH 터널을 열고 닫는다.
#
#   ./scripts/tunnel.sh start
#   ./scripts/tunnel.sh status
#   ./scripts/tunnel.sh stop
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
SOCKET="$ROOT/.ssh-tunnel.sock"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "error: .env가 없습니다. .env.example을 복사해 값을 채우세요." >&2
  exit 1
fi

# .env에서 SSH_/TUNNEL_ 키만 읽는다. DB_PASSWORD 등은 셸로 가져오지 않는다.
while IFS='=' read -r key value || [[ -n "$key" ]]; do
  [[ "$key" =~ ^(SSH|TUNNEL)_[A-Z_]+$ ]] || continue
  printf -v "$key" '%s' "${value%$'\r'}"
done < "$ENV_FILE"

SSH_HOST="${SSH_HOST:-}"
SSH_USER="${SSH_USER:-}"
SSH_KEY_PATH="${SSH_KEY_PATH:-}"
SSH_PORT="${SSH_PORT:-22}"
TUNNEL_LOCAL_PORT="${TUNNEL_LOCAL_PORT:-15432}"
TUNNEL_REMOTE_HOST="${TUNNEL_REMOTE_HOST:-127.0.0.1}"
TUNNEL_REMOTE_PORT="${TUNNEL_REMOTE_PORT:-5432}"

for var in SSH_HOST SSH_USER SSH_KEY_PATH; do
  if [[ -z "${!var}" ]]; then
    echo "error: .env에 $var 값이 없습니다." >&2
    exit 1
  fi
done

SSH_KEY_PATH="${SSH_KEY_PATH/#\~/$HOME}"

ssh_ctl() {
  ssh -S "$SOCKET" -O "$1" -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" 2>/dev/null
}

start() {
  if ssh_ctl check; then
    echo "이미 열려 있습니다: localhost:$TUNNEL_LOCAL_PORT"
    return 0
  fi

  if [[ ! -f "$SSH_KEY_PATH" ]]; then
    echo "error: 키 파일이 없습니다: $SSH_KEY_PATH" >&2
    exit 1
  fi

  local perm
  perm="$(stat -f '%Lp' "$SSH_KEY_PATH" 2>/dev/null || stat -c '%a' "$SSH_KEY_PATH")"
  if [[ "$perm" != "600" && "$perm" != "400" ]]; then
    echo "error: 키 파일 권한이 $perm 입니다. chmod 600 $SSH_KEY_PATH 후 다시 실행하세요." >&2
    exit 1
  fi

  rm -f "$SOCKET"
  ssh -f -N -M -S "$SOCKET" \
    -i "$SSH_KEY_PATH" \
    -p "$SSH_PORT" \
    -o ExitOnForwardFailure=yes \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -L "$TUNNEL_LOCAL_PORT:$TUNNEL_REMOTE_HOST:$TUNNEL_REMOTE_PORT" \
    "$SSH_USER@$SSH_HOST"

  echo "터널 열림: localhost:$TUNNEL_LOCAL_PORT -> $TUNNEL_REMOTE_HOST:$TUNNEL_REMOTE_PORT ($SSH_HOST)"
}

stop() {
  if ssh_ctl exit; then
    echo "터널 닫힘"
  else
    echo "열려 있는 터널이 없습니다"
  fi
  rm -f "$SOCKET"
}

status() {
  if ssh_ctl check; then
    echo "열림: localhost:$TUNNEL_LOCAL_PORT -> $TUNNEL_REMOTE_HOST:$TUNNEL_REMOTE_PORT ($SSH_HOST)"
  else
    echo "닫힘"
    exit 1
  fi
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *)
    echo "usage: $0 {start|stop|status}" >&2
    exit 1
    ;;
esac
