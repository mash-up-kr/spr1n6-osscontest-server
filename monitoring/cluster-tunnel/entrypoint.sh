#!/bin/sh
# 클러스터로 가는 SSH 터널을 열고, 지표로 나오지 않는 상태(VIP 위치·OpenProxy 응답)를 확인하는 node_probe.py 를 띄운다.
#
# Patroni·etcd·OpenProxy(6432)·VIP 는 노드 사이에 열린 포트라 db-01 연결 하나로 연다.
# OpenProxy 지표 포트(9930)는 노드 사이에도 막혀 있어 db-02·db-03 에 SSH 로 들어가 그 서버 안에서 연다.
#
#   이 컨테이너 포트   가는 곳
#   8001~8003         db-01~03 Patroni REST (8008)
#   2381~2383         db-01~03 etcd (2379)
#   6402·6403         db-02·03 OpenProxy (6432)
#   6400              VIP 10.40.1.100 의 OpenProxy (6432)
#   9932·9933         db-02·03 OpenProxy 지표 (9930)
#   9100              node_probe.py
set -eu

apk add --no-cache openssh-client python3 >/dev/null
cp /key /tmp/id && chmod 600 /tmp/id

JUMP="${SSH_USER}@${SSH_HOST}"
SSH_OPTS="-i /tmp/id -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/tmp/known_hosts -o ServerAliveInterval=15 -o ServerAliveCountMax=3"

# 끊기면 2초 뒤 다시 연다. 하나가 끊겨도 나머지 터널은 그대로 남는다.
keep() {
  while true; do
    "$@" || true
    sleep 2
  done
}

keep ssh -N $SSH_OPTS -p "$SSH_PORT" -o ExitOnForwardFailure=yes \
  -L 0.0.0.0:8001:10.40.1.11:8008 -L 0.0.0.0:8002:10.40.1.12:8008 -L 0.0.0.0:8003:10.40.1.13:8008 \
  -L 0.0.0.0:2381:10.40.1.11:2379 -L 0.0.0.0:2382:10.40.1.12:2379 -L 0.0.0.0:2383:10.40.1.13:2379 \
  -L 0.0.0.0:6402:10.40.1.12:6432 -L 0.0.0.0:6403:10.40.1.13:6432 \
  -L 0.0.0.0:6400:10.40.1.100:6432 \
  "$JUMP" &

# db-02·db-03 는 db-01 을 거쳐 들어간다. node_probe.py 가 이 연결의 제어 소켓으로 VIP 위치를 묻는다.
# 연결이 비정상 종료되면 소켓 파일이 남아 새 연결이 소켓을 못 만들므로 열기 전에 지운다.
node_tunnel() {
  n="$1"
  rm -f "/tmp/cm-db-0$n"
  ssh -N $SSH_OPTS -o ExitOnForwardFailure=yes -M -S "/tmp/cm-db-0$n" \
    -o ProxyCommand="ssh $SSH_OPTS -p $SSH_PORT -W %h:%p $JUMP" \
    -L "0.0.0.0:993$n:127.0.0.1:9930" \
    "${SSH_USER}@10.40.1.1$n"
}
keep node_tunnel 2 &
keep node_tunnel 3 &

exec python3 /opt/monitoring/node_probe.py
