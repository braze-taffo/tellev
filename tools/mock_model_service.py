#!/usr/bin/env python3
"""Local mock OpenAI-compatible model service for Tellev creation verification.

- GET  /v1/models            -> two fixture models
- POST /v1/chat/completions  -> logs the request body (model + messages) to
                                requests.log and returns a deterministic reply:
                                prose when the round carries no tool call,
                                a native creation_tool call otherwise.

Never contacts any real provider. Bind to loopback only.
"""
import json
import sys
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODELS = ["fixture-model-a", "fixture-model-b"]
LOG_PATH = "requests.log"
_lock = threading.Lock()


def record(entry):
    with _lock:
        with open(LOG_PATH, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")


def chat_reply(body):
    messages = body.get("messages") or []
    has_tool_result = any(
        "<tool_result" in str((m or {}).get("content") or "") for m in messages
    )
    if has_tool_result:
        content = "已收到工具结果，角色卡草稿正在更新。"
    else:
        content = "已记录你的需求，我会先补全角色名称与简介。"
    return {
        "id": "chatcmpl-fixture",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": body.get("model"),
        "choices": [
            {
                "index": 0,
                "delta": {"role": "assistant", "content": content},
                "finish_reason": None,
            }
        ],
    }


def tool_reply(body):
    arguments = {
        "name": "set_card_fields",
        "arguments": {"name": "校验角色", "description": "由本地校验服务写入"},
    }
    return {
        "id": "chatcmpl-fixture",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": body.get("model"),
        "choices": [
            {
                "index": 0,
                "delta": {
                    "role": "assistant",
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": "call_fixture_1",
                            "type": "function",
                            "function": {
                                "name": "creation_tool",
                                "arguments": json.dumps(arguments, ensure_ascii=False),
                            },
                        }
                    ],
                },
                "finish_reason": None,
            }
        ],
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _stream(self, chunks):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for chunk in chunks:
            line = "data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n"
            data = line.encode("utf-8")
            self.wfile.write(("%x\r\n" % len(data)).encode("ascii") + data + b"\r\n")
            self.wfile.flush()
        done = b"data: [DONE]\n\n"
        self.wfile.write(("%x\r\n" % len(done)).encode("ascii") + done + b"\r\n")
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            self._send({
                "object": "list",
                "data": [{"id": m, "object": "model"} for m in MODELS],
            })
        else:
            self._send({"error": "not found"}, status=404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw.decode("utf-8"))
        except Exception:
            body = {"_unparsed": raw[:400].decode("utf-8", "replace")}
        record({
            "at": datetime.now().isoformat(timespec="seconds"),
            "path": self.path,
            "model": body.get("model"),
            "stream": body.get("stream"),
            "message_count": len(body.get("messages") or []),
            "messages": body.get("messages"),
        })
        if not self.path.startswith("/v1/chat/completions"):
            self._send({"error": "not found"}, status=404)
            return
        joined = json.dumps(body.get("messages") or [], ensure_ascii=False)
        wants_optimize = "optimized" in joined and "notes" in joined
        if wants_optimize:
            content = json.dumps(
                {"optimized": "优化后的提示词：请描述角色", "notes": "本地校验服务"},
                ensure_ascii=False,
            )
            chunk = {
                "id": "chatcmpl-fixture",
                "object": "chat.completion.chunk",
                "created": 0,
                "model": body.get("model"),
                "choices": [
                    {"index": 0, "delta": {"role": "assistant", "content": content},
                     "finish_reason": None}
                ],
            }
            tail = {
                "id": "chatcmpl-fixture",
                "object": "chat.completion.chunk",
                "created": 0,
                "model": body.get("model"),
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
            }
            self._stream([chunk, tail])
            return
        has_tool_result = any(
            "<tool_result" in str((m or {}).get("content") or "")
            for m in (body.get("messages") or [])
        )
        if has_tool_result:
            chunk = chat_reply(body)
        else:
            chunk = tool_reply(body)
        tail = {
            "id": "chatcmpl-fixture",
            "object": "chat.completion.chunk",
            "created": 0,
            "model": body.get("model"),
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
        }
        self._stream([chunk, tail])


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("mock model service on 127.0.0.1:%d" % port, flush=True)
    server.serve_forever()
