#!/usr/bin/env python3
"""DashScope 兼容本地 stub：把"网关自身容量"与"上游供应商限额"分离测量。

为什么需要它（2026-11-02 压测收口）：unique 场景下每个请求都要走真实上游，DashScope 的
QPM 限速（实测 ~8 RPS 就开始 429）先于任何网关侧瓶颈生效——拐点测出来的是供应商的，
不是网关的。本 stub 实现三条出站契约（embedding / rerank / chat SSE），延迟可用环境变量
标定到与 live 相当，让并发扫描在零外部依赖、零 token 成本下跑满档位。

实现口径：
- **确定性向量**：embedding 用文本哈希播种的归一化伪随机向量——同一文本恒得同一向量，
  因此语料可以用 stub 重新 ingest，查询向量与语料向量匹配，检索质量行为与 live 同构。
- **延迟可标定**：STUB_EMBED_MS / STUB_RERANK_MS / STUB_LLM_TTFT_MS / STUB_LLM_TOKENS /
  STUB_LLM_TOKEN_MS，默认近似 2026-09-25 live 画像（80/150/500/12/25）。
- **并发模型**：thread-per-request（sleep 不占 CPU），数百并发无压力；stub 自身绝不能
  成为被测瓶颈，压测时若怀疑，先看 stub 侧 CPU。

端点契约（与 EmbeddingClient / RerankClient / LlmClient 逐字段对齐）：
- POST /compatible-mode/v1/embeddings                     -> {"data":[{"embedding":[...]}]}
- POST /api/v1/services/rerank/text-rerank/text-rerank    -> {"output":{"results":[...]}}
- POST /compatible-mode/v1/chat/completions (stream=true) -> SSE: delta 帧 × N + [DONE]

用法：
  python load/dashscope_stub.py                # 默认 127.0.0.1:8082
  STUB_EMBED_MS=80 python load/dashscope_stub.py
网关侧：DASHSCOPE_BASE_URL=http://127.0.0.1:8082 DASHSCOPE_API_KEY=stub DASHSCOPE_MODE=live
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import random
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = os.environ.get("STUB_HOST", "127.0.0.1")
PORT = int(os.environ.get("STUB_PORT", "8082"))
EMBED_MS = int(os.environ.get("STUB_EMBED_MS", "80"))
RERANK_MS = int(os.environ.get("STUB_RERANK_MS", "150"))
LLM_TTFT_MS = int(os.environ.get("STUB_LLM_TTFT_MS", "500"))
LLM_TOKENS = int(os.environ.get("STUB_LLM_TOKENS", "12"))
LLM_TOKEN_MS = int(os.environ.get("STUB_LLM_TOKEN_MS", "25"))
DIM = int(os.environ.get("STUB_DIM", "1024"))

ANSWER = ("建议按以下顺序排查：1) 确认该错误码对应的监控面板与最近变更窗口；"
          "2) 检查相关服务日志中的同一 trace_id；3) 按只读命令清单核对当前状态后再执行人工处置。")


def deterministic_vector(text: str, dim: int) -> list[float]:
    """文本哈希播种 → 归一化均匀向量。同一文本恒等，跨进程跨重启恒等。"""
    rng = random.Random(hashlib.sha256(text.encode("utf-8")).digest())
    v = [rng.gauss(0.0, 1.0) for _ in range(dim)]
    norm = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / norm for x in v]


def fake_embed_json(texts: list[str]) -> bytes:
    data = [{"index": i, "embedding": deterministic_vector(t, DIM)} for i, t in enumerate(texts)]
    return json.dumps({"output": {"embeddings": data}, "data": data}).encode("utf-8")


def fake_rerank_json(query: str, documents: list[str], top_n: int) -> bytes:
    """确定性伪相关：按 sha256(query+doc) 排序，分数压在 [0.55, 0.95]——高过置信门控。"""
    scored = sorted(
        ((i, d, 0.55 + (hashlib.sha256((query + d).encode()).hexdigest()[0] / 255) * 0.4)
         for i, d in enumerate(documents)),
        key=lambda x: -x[2])[:top_n]
    results = [{"index": i, "relevance_score": round(s, 4)} for i, _, s in scored]
    return json.dumps({"output": {"results": results}}).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # 静默：stub 日志噪音不影响压测观察窗口
        pass

    def _read_body(self) -> dict:
        # JDK HttpClient 对大 body 走 Transfer-Encoding: chunked（实测 2026-11-02，
        # 且伴随 h2c 升级头——h2c 不被接受时 Java 自动回落 1.1，chunked 保留）。
        # BaseHTTPRequestHandler 不替你解 chunked，这里手动按 RFC7230 读。
        if (self.headers.get("Transfer-Encoding") or "").lower() == "chunked":
            chunks = []
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    while True:  # 尾随头：读到空行（可能没有）
                        line = self.rfile.readline()
                        if line in (b"\r\n", b"\n", b""):
                            break
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)  # 块尾 CRLF
            payload = b"".join(chunks)
        else:
            payload = self.rfile.read(int(self.headers.get("Content-Length", "0") or 0))
        return json.loads(payload or b"{}")

    def _json(self, payload: bytes, code: int = 200) -> None:
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        try:
            self._do_post()
        except (BrokenPipeError, ConnectionResetError):
            raise
        except Exception:
            # 压测诊断：任何 5xx 都会把栈打出来——stub 必须可观察，静默 5xx 会把
            # "stub 缺陷"伪装成"网关瓶颈"，污染整份报告。
            import traceback
            traceback.print_exc()
            self._json(b'{"error":"stub internal"}', 500)

    def _do_post(self):
        print(f"POST {self.path} CL={self.headers.get('Content-Length')} "
              f"TE={self.headers.get('Transfer-Encoding')} "
              f"UPG={self.headers.get('Upgrade')}", flush=True)
        body = self._read_body()
        if self.path.endswith("/embeddings"):
            time.sleep(EMBED_MS / 1000.0)
            texts = body.get("input") or []
            if texts and isinstance(texts[0], list):  # 兼容多模态入参形态
                texts = [" ".join(map(str, t)) if not isinstance(t, str) else t for t in texts]
            self._json(fake_embed_json(list(texts)))
        elif self.path.endswith("/text-rerank"):
            time.sleep(RERANK_MS / 1000.0)
            inp = body.get("input", {})
            docs = inp.get("documents", [])
            top_n = body.get("parameters", {}).get("top_n", len(docs))
            self._json(fake_rerank_json(inp.get("query", ""), docs, min(top_n, len(docs))))
        elif self.path.endswith("/chat/completions"):
            time.sleep(LLM_TTFT_MS / 1000.0)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            chunk = json.dumps({"choices": [{"delta": {"content": ANSWER}}]}).encode("utf-8")
            try:
                for _ in range(LLM_TOKENS):
                    self.wfile.write(b"data: " + chunk + b"\n\n")
                    self.wfile.flush()
                    time.sleep(LLM_TOKEN_MS / 1000.0)
                self.wfile.write(b"data: [DONE]\n\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass  # 客户端断开是常态（准入取消/压测超时），不是 stub 缺陷
        else:
            self._json(b'{"error":"stub: unknown path"}', 404)


if __name__ == "__main__":
    threading.current_thread().name = "stub-main"
    print(f"dashscope stub on http://{HOST}:{PORT} "
          f"(embed={EMBED_MS}ms rerank={RERANK_MS}ms llm_ttft={LLM_TTFT_MS}ms "
          f"tokens={LLM_TOKENS}x{LLM_TOKEN_MS}ms dim={DIM})", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
