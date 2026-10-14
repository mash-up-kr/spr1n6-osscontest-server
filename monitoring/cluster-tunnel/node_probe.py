"""Patroni·etcd·OpenProxy 가 지표로 내보내지 않는 상태를 직접 확인해 Prometheus 지표로 낸다.

  opensql_openproxy_up{node,host}        OpenProxy(6432)가 PostgreSQL 프로토콜로 응답하면 1. node="vip" 는 VIP 주소로 확인한 값
  opensql_vip_assigned{node,host}        VIP 가 그 서버 네트워크에 붙어 있으면 1
  opensql_vip_check_success{node,host}   VIP 확인 명령이 성공하면 1. 0 이면 opensql_vip_assigned 를 내지 않는다

Prometheus 가 /metrics 를 읽을 때마다 확인한다. 포트와 제어 소켓은 entrypoint.sh 가 연다.
"""
import socket
import subprocess
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VIP = "10.40.1.100"
TIMEOUT_SECONDS = 1.0

# (node, host, 이 컨테이너에서 본 6432 터널 포트)
OPENPROXY_TARGETS = [
    ("db-02", "10.40.1.12", 6402),
    ("db-03", "10.40.1.13", 6403),
    ("vip", VIP, 6400),
]
# (node, host, SSH 제어 소켓)
VIP_CHECK_TARGETS = [
    ("db-02", "10.40.1.12", "/tmp/cm-db-02"),
    ("db-03", "10.40.1.13", "/tmp/cm-db-03"),
]

# PostgreSQL SSLRequest. 터널은 원격이 죽어 있어도 연결을 받으므로 연결만으로는 판단하지 않고 응답('S' 또는 'N')을 기다린다.
SSL_REQUEST = b"\x00\x00\x00\x08\x04\xd2\x16\x2f"


def openproxy_up(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=TIMEOUT_SECONDS) as conn:
            conn.settimeout(TIMEOUT_SECONDS)
            conn.sendall(SSL_REQUEST)
            return 1 if conn.recv(1) in (b"S", b"N") else 0
    except OSError:
        return 0


def vip_assigned(control_socket):
    """(확인 성공 여부, VIP 보유 여부). 확인에 실패하면 보유 여부는 None 이다."""
    command = ["ssh", "-S", control_socket, "-o", "ControlMaster=no", "-o", "BatchMode=yes", "node", "ip -4 -o addr show"]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        return 0, None
    if result.returncode != 0:
        return 0, None
    return 1, int(f" {VIP}/" in result.stdout)


def render_metrics():
    with ThreadPoolExecutor(max_workers=len(OPENPROXY_TARGETS) + len(VIP_CHECK_TARGETS)) as pool:
        proxy = [(node, host, pool.submit(openproxy_up, port)) for node, host, port in OPENPROXY_TARGETS]
        vip = [(node, host, pool.submit(vip_assigned, sock)) for node, host, sock in VIP_CHECK_TARGETS]

    lines = [
        "# HELP opensql_openproxy_up OpenProxy answered a PostgreSQL SSLRequest.",
        "# TYPE opensql_openproxy_up gauge",
    ]
    lines += [f'opensql_openproxy_up{{node="{node}",host="{host}"}} {f.result()}' for node, host, f in proxy]

    checks = [(node, host, *f.result()) for node, host, f in vip]
    lines += [
        "# HELP opensql_vip_check_success The VIP check command on the node succeeded.",
        "# TYPE opensql_vip_check_success gauge",
    ]
    lines += [f'opensql_vip_check_success{{node="{node}",host="{host}"}} {ok}' for node, host, ok, _ in checks]
    lines += [
        "# HELP opensql_vip_assigned The VIP is assigned to the node's network interface.",
        "# TYPE opensql_vip_assigned gauge",
    ]
    lines += [f'opensql_vip_assigned{{node="{node}",host="{host}"}} {held}' for node, host, ok, held in checks if ok]
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/metrics":
            self.send_error(404)
            return
        body = render_metrics().encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # 2초마다 오는 수집 요청을 로그로 남기지 않는다.
    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 9100), Handler).serve_forever()
