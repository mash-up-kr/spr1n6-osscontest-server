# Copyright (c) 2026 매쉬업스프링YB
# SPDX-License-Identifier: MIT

"""H-2 에서 server 를 부른다. 웹 검색(REST), MCP 검색(Streamable HTTP), 문서 업로드(쓰기)다.

쿼리가 어느 DB 노드에서 처리됐는지는 server 가 아니라 DB(pg_stat_activity·행 조회)에서 확인한다. 여기서는 요청만 보낸다.
요청 형식은 server 가 소유한다(docs/API_SPEC.md 5장·8장·10장).
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Optional

from .config import Config

# MCP 세션을 열 때 밝히는 프로토콜 버전. server 가 다른 버전을 쓰면 응답에 그 버전이 오고, 이후 요청은 그 값을 쓴다.
MCP_PROTOCOL_VERSION = "2025-06-18"


@dataclass
class CallResult:
    ok: bool
    detail: str


@dataclass
class UploadResult:
    ok: bool
    detail: str
    document_id: Optional[int] = None


class AppError(Exception):
    """server 가 기대한 형식으로 답하지 않았다."""


class AppClient:
    def __init__(self, config: Config, timeout: float = 15.0):
        self.config = config
        self.timeout = timeout
        self._mcp_session: Optional[str] = None
        self._mcp_version = MCP_PROTOCOL_VERSION
        self._mcp_next_id = 1

    def reachable(self) -> bool:
        try:
            with urllib.request.urlopen(f"{self.config.app_url}/actuator/health", timeout=self.timeout) as response:
                return response.status == 200
        except (urllib.error.URLError, OSError, ValueError):
            return False

    def web_search(self, query: str) -> CallResult:
        # 재정렬(Cohere)은 끈다. 외부 API 호출이 섞이면 재려는 DB 부하가 흐려지고, 유료 API 를 수백 번 부른다.
        payload = json.dumps({"query": query, "topK": 10, "rerank": False}).encode()
        headers = {"Content-Type": "application/json", "X-User-Id": self.config.app_user_id}
        try:
            status, _, _ = self._request("POST", "/api/v1/search", payload, headers)
        except AppError as error:
            return CallResult(ok=False, detail=str(error))
        return CallResult(ok=status == 200, detail=f"HTTP {status}")

    def mcp_search(self, query: str) -> CallResult:
        """MCP search_documents 를 부른다. 세션은 처음 한 번 열고 이어서 쓴다."""
        try:
            if self._mcp_session is None:
                self._open_mcp_session()
            response = self._mcp_call("tools/call", {"name": "search_documents", "arguments": {"query": query, "topK": 10, "rerank": False}})
        except AppError as error:
            # 세션이 만료됐을 수 있다. 다음 호출에서 새로 연다.
            self._mcp_session = None
            return CallResult(ok=False, detail=str(error))
        if "error" in response:
            return CallResult(ok=False, detail=f"JSON-RPC 오류 {response['error'].get('code')}")
        if (response.get("result") or {}).get("isError"):
            return CallResult(ok=False, detail="도구가 오류로 응답")
        return CallResult(ok=True, detail="ok")

    def upload(self, filename: str, content: bytes, title: str) -> UploadResult:
        """문서 생성 + 1번 버전 업로드. server 는 202 와 documentId 를 돌려준다."""
        boundary = f"ha-test-{uuid.uuid4().hex}"
        body = b"".join(
            [
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                "Content-Type: text/markdown\r\n\r\n".encode(),
                content,
                f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="title"\r\n\r\n{title}\r\n'
                f"--{boundary}--\r\n".encode(),
            ]
        )
        headers = {"Content-Type": f"multipart/form-data; boundary={boundary}", "X-User-Id": self.config.app_user_id}
        try:
            status, _, text = self._request("POST", "/api/v1/documents", body, headers)
        except AppError as error:
            return UploadResult(ok=False, detail=str(error))
        if status != 202:
            return UploadResult(ok=False, detail=f"HTTP {status}")
        try:
            return UploadResult(ok=True, detail=f"HTTP {status}", document_id=int(json.loads(text)["documentId"]))
        except (ValueError, KeyError, TypeError):
            return UploadResult(ok=False, detail=f"HTTP {status}, documentId 없음")

    def delete(self, document_id: int) -> CallResult:
        try:
            status, _, _ = self._request("DELETE", f"/api/v1/documents/{document_id}", None, {"X-User-Id": self.config.app_user_id})
        except AppError as error:
            return CallResult(ok=False, detail=str(error))
        return CallResult(ok=200 <= status < 300, detail=f"HTTP {status}")

    # MCP Streamable HTTP: initialize 로 세션 ID 를 받고, initialized 알림을 보낸 뒤에야 도구를 부를 수 있다.

    def _open_mcp_session(self) -> None:
        response, session = self._mcp_post(
            {
                "jsonrpc": "2.0",
                "id": self._take_id(),
                "method": "initialize",
                "params": {"protocolVersion": MCP_PROTOCOL_VERSION, "capabilities": {}, "clientInfo": {"name": "ha-test", "version": "1"}},
            }
        )
        if not session:
            raise AppError("MCP initialize 응답에 Mcp-Session-Id 가 없다")
        self._mcp_session = session
        self._mcp_version = (response.get("result") or {}).get("protocolVersion") or MCP_PROTOCOL_VERSION
        self._mcp_post({"jsonrpc": "2.0", "method": "notifications/initialized"}, expect_response=False)

    def _mcp_call(self, method: str, params: dict) -> dict:
        response, _ = self._mcp_post({"jsonrpc": "2.0", "id": self._take_id(), "method": method, "params": params})
        return response

    def _mcp_post(self, message: dict, expect_response: bool = True):
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "X-Search-User-Id": self.config.app_user_id,
        }
        if self._mcp_session:
            headers["Mcp-Session-Id"] = self._mcp_session
            headers["MCP-Protocol-Version"] = self._mcp_version
        status, response_headers, text = self._request("POST", "/mcp", json.dumps(message).encode(), headers)
        if not expect_response:
            if status not in (200, 202):
                raise AppError(f"MCP 알림 HTTP {status}")
            return {}, None
        if status != 200:
            raise AppError(f"MCP HTTP {status}")
        content_type = response_headers.get("Content-Type", "")
        response = _find_response(text, message["id"], event_stream="text/event-stream" in content_type)
        return response, response_headers.get("Mcp-Session-Id")

    def _take_id(self) -> int:
        self._mcp_next_id += 1
        return self._mcp_next_id

    def _request(self, method: str, path: str, body: Optional[bytes], headers: dict):
        request = urllib.request.Request(f"{self.config.app_url}{path}", data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return response.status, response.headers, response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            # 4xx·5xx 도 응답이다. 상태 코드를 그대로 돌려 호출한 쪽이 판정하게 하고, 연결은 닫는다.
            with error:
                return error.code, error.headers, error.read().decode("utf-8", "replace")
        except (urllib.error.URLError, OSError, ValueError) as error:
            raise AppError(str(error)[:80]) from None


def _find_response(text: str, request_id: int, event_stream: bool) -> dict:
    """JSON 본문이나 SSE 스트림(data: 줄)에서 request_id 에 대한 JSON-RPC 응답을 찾는다."""
    if event_stream:
        candidates = [line[5:].strip() for line in text.splitlines() if line.startswith("data:")]
    else:
        candidates = [text]
    for candidate in candidates:
        try:
            message = json.loads(candidate)
        except ValueError:
            continue
        if isinstance(message, dict) and message.get("id") == request_id:
            return message
    raise AppError("MCP 응답에서 요청 id 에 맞는 결과를 찾지 못했다")


class SimAppClient:
    """모의 실행용. 요청을 보내지 않고 성공을 돌려준다. 업로드한 문서는 모의 클러스터의 Primary 에 남긴다."""

    def __init__(self, config: Config, cluster=None):
        self.config = config
        self.cluster = cluster

    def reachable(self) -> bool:
        return True

    def web_search(self, query: str) -> CallResult:
        return CallResult(ok=True, detail="HTTP 200 (모의)")

    def mcp_search(self, query: str) -> CallResult:
        return CallResult(ok=True, detail="ok (모의)")

    def upload(self, filename: str, content: bytes, title: str) -> UploadResult:
        document_id = self.cluster.add_document() if self.cluster is not None else 1
        return UploadResult(ok=True, detail="HTTP 202 (모의)", document_id=document_id)

    def delete(self, document_id: int) -> CallResult:
        return CallResult(ok=True, detail="HTTP 204 (모의)")
