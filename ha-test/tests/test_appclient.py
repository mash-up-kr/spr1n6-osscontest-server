# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""H-2 앱 호출. server 와 같은 규칙으로 답하는 가짜 HTTP 서버를 띄워 요청 형식을 확인한다.

가짜 MCP 는 Streamable HTTP 처럼 initialize 로 세션을 열지 않은 도구 호출을 400 으로 거절하고,
도구 응답은 SSE(text/event-stream)로 보낸다. 실제 server 에서 H-2 가 늘 실패하던 원인을 막는 테스트다.
"""
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from hatest.appclient import AppClient
from hatest.config import load_config

SESSION = "session-abc"


class FakeServer(BaseHTTPRequestHandler):
    seen = []

    def log_message(self, *args):
        pass

    def _body(self):
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def _send(self, status, body=b"", content_type="application/json", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._send(200, b'{"status":"UP"}') if self.path == "/actuator/health" else self._send(404)

    def do_DELETE(self):
        FakeServer.seen.append(("DELETE", self.path))
        self._send(204)

    def do_POST(self):
        body = self._body()
        if self.path == "/api/v1/search":
            FakeServer.seen.append(("search", json.loads(body), self.headers.get("X-User-Id")))
            self._send(200, b'{"items":[]}')
        elif self.path == "/api/v1/documents":
            FakeServer.seen.append(("upload", body, self.headers.get("Content-Type")))
            self._send(202, b'{"documentId":42,"versionNo":1,"indexing":{"status":"PENDING"}}')
        elif self.path == "/mcp":
            self._mcp(json.loads(body))
        else:
            self._send(404)

    def _mcp(self, message):
        FakeServer.seen.append(("mcp", message.get("method"), self.headers.get("Mcp-Session-Id")))
        if message.get("method") == "initialize":
            result = {"jsonrpc": "2.0", "id": message["id"], "result": {"protocolVersion": "2025-06-18", "capabilities": {}}}
            self._send(200, json.dumps(result).encode(), headers={"Mcp-Session-Id": SESSION})
            return
        if self.headers.get("Mcp-Session-Id") != SESSION:
            self._send(400, b'{"error":"Session ID required in mcp-session-id header"}')
            return
        if message.get("method") == "notifications/initialized":
            self._send(202)
            return
        FakeServer.seen.append(("tool-args", message["params"]["arguments"]))
        result = {"jsonrpc": "2.0", "id": message["id"], "result": {"content": [{"type": "text", "text": "[]"}], "isError": False}}
        self._send(200, f"event: message\ndata: {json.dumps(result)}\n\n".encode(), content_type="text/event-stream")


class AppClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeServer)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.config = load_config(environ={"HA_APP_URL": f"http://127.0.0.1:{cls.server.server_port}"})

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        FakeServer.seen = []

    def test_mcp_search_opens_session_before_calling_tool(self):
        client = AppClient(self.config)
        self.assertTrue(client.mcp_search("국회").ok)
        self.assertTrue(client.mcp_search("예산").ok)
        methods = [entry[1] for entry in FakeServer.seen if entry[0] == "mcp"]
        # 세션은 한 번만 열고, 두 번째 검색은 같은 세션으로 도구만 부른다.
        self.assertEqual(methods, ["initialize", "notifications/initialized", "tools/call", "tools/call"])

    def test_mcp_tool_call_without_session_is_rejected_by_server(self):
        # 고치기 전 동작(세션 없이 tools/call)은 server 가 거절한다. 이 단언이 깨지면 가짜 서버가 규칙을 안 지키는 것이다.
        client = AppClient(self.config)
        client._mcp_session = None
        with self.assertRaises(Exception):
            client._mcp_call("tools/call", {"name": "search_documents", "arguments": {"query": "x"}})

    def test_searches_turn_rerank_off(self):
        client = AppClient(self.config)
        client.web_search("국회")
        client.mcp_search("국회")
        web = [entry[1] for entry in FakeServer.seen if entry[0] == "search"][0]
        tool = [entry[1] for entry in FakeServer.seen if entry[0] == "tool-args"][0]
        self.assertIs(web["rerank"], False)
        self.assertIs(tool["rerank"], False)

    def test_upload_sends_multipart_file_and_title(self):
        result = AppClient(self.config).upload("ha-test.md", "# 내용\n".encode(), "ha-test 문서")
        self.assertTrue(result.ok)
        self.assertEqual(result.document_id, 42)
        _, body, content_type = [entry for entry in FakeServer.seen if entry[0] == "upload"][0]
        self.assertIn("multipart/form-data; boundary=", content_type)
        self.assertIn(b'name="file"; filename="ha-test.md"', body)
        self.assertIn('name="title"\r\n\r\nha-test 문서'.encode(), body)

    def test_delete_and_health(self):
        client = AppClient(self.config)
        self.assertTrue(client.reachable())
        self.assertTrue(client.delete(42).ok)
        self.assertIn(("DELETE", "/api/v1/documents/42"), FakeServer.seen)


if __name__ == "__main__":
    unittest.main()
