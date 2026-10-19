"""本机 OpsPilot API 共享辅助 —— 验收/评测脚本的单一事实源。

收敛此前散落在 acceptance_a2 / acceptance_a3 / evaluate / console_client 的
重复实现（_assert_local / load_tokens / search_docs / post）。
安全口径不变：仅允许访问本机 http 服务（localhost 白名单），阻断 SSRF。
token 路径相对本文件解析，不再依赖运行目录（修复 a3/evaluate 的 cwd 脆弱）。
"""
from __future__ import annotations

import ipaddress
import json
import os
import socket
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse


def _resolve_base(env) -> str:
    """解析服务基址。抽成纯函数是为了可单测——模块级常量无法在不 reload 的情况下验证。

    为什么可配置（2026-09-24 补）：此前 BASE 硬编码 8081，而 `console_client` 早有
    `OPSPILOT_BASE` 覆盖口，两者不对称。本机 8081 被无关进程占用时，所有基于 localapi 的
    验收/评测脚本（acceptance_a2/a3、evaluate、四个 live 测量脚本、alert_producer…）都**无法**
    改指到别的端口，整条 live 验证链就此卡死。补上覆盖口后，端口冲突不再等于验证不可做。

    安全性不受影响：覆盖口只改**默认值**，`assert_local` 仍是唯一执行点（逐请求校验 scheme、
    环回白名单、解析后 IP、拒 userinfo）。把 base 指到非环回地址一样会被拒。
    """
    return str(env.get("OPSPILOT_BASE", "http://localhost:8081")).rstrip("/")


BASE = _resolve_base(os.environ)
ALLOWED_HOSTS = {"localhost", "127.0.0.1", "::1"}
_TOKENS = Path(__file__).resolve().parent.parent / "scripts" / "redteam_tokens.txt"

# 合法账号名 → 登录用户名（P2 起口令登录，token 不再落盘）。口令只从 DEMO_PASSWORD 环境变量读。
ACCOUNTS = {"sre_l1": "sre-limited", "sre_l3": "sre-full", "sre_acme": "sre-acme"}

# 验收/评测脚本 `--user` 的默认主体（单一事实源）。
# 为什么要有这个常量：`ACCOUNTS` 的**键**（sre_l3）是内部别名，不是真实用户名——曾有两个脚本
# 直接把键当成用户名传进 login()，跑起来必然 401 并触发登录失败锁定（真实用户名是 sre-full）。
# 由 test_localapi.py 现算 seed_demo_users.sh 的播种集合来锁死它，防同类误用复发。
DEFAULT_EVAL_USER = ACCOUNTS["sre_l3"]


def assert_local(url: str) -> str:
    """出口白名单：仅 http + 环回主机，且**解析后的 IP 也必须仍是环回**。

    只比主机名字符串不够：hosts 文件被改或 DNS rebinding 都能让 `localhost` 指向远端，
    那样"本机白名单"就变成了通往内网的跳板。故这里解析一次 IP 并逐条校验。
    """
    p = urlparse(url)
    if p.scheme != "http" or p.hostname not in ALLOWED_HOSTS:
        raise ValueError(f"验收脚本仅允许本机 http 服务，拒绝: {url}")
    if p.username or p.password:
        raise ValueError(f"URL 不得携带 userinfo（混淆手法），拒绝: {url}")
    try:
        infos = socket.getaddrinfo(p.hostname, p.port or 80, type=socket.SOCK_STREAM)
    except socket.gaierror as e:
        raise ValueError(f"主机名无法解析: {p.hostname}") from e
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if not ip.is_loopback:
            raise ValueError(f"主机名解析到非环回地址，拒绝: {p.hostname} -> {ip}")
    return url


class _LoopbackOnlyRedirect(urllib.request.HTTPRedirectHandler):
    """禁止跟随任何跳出白名单的重定向。

    urllib 默认跟随 3xx：本机服务若被污染或误配而重定向到外部地址，等于白名单被绕过。
    重定向目标必须重新过一遍 assert_local，否则直接拒绝。
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        assert_local(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


_OPENER = urllib.request.build_opener(_LoopbackOnlyRedirect)


def _open(req: urllib.request.Request, timeout: int):
    """统一出口：所有 HTTP 调用都经此（白名单 + 不跟随越界重定向）。"""
    return _OPENER.open(req, timeout=timeout)


def get_json(path: str, token: str, timeout: int = 10) -> dict:
    req = urllib.request.Request(assert_local(BASE + path), method="GET",
                                 headers={"Authorization": "Bearer " + token})
    with _open(req, timeout) as r:
        return json.load(r)


def get_text(path: str, timeout: int = 15) -> str:
    """GET 一段**非 JSON** 文本（当前唯一用途：/actuator/prometheus 的 Prometheus 文本格式
    ——get_json 用 json.load 读它必抛）。该端点不在 JwtAuthFilter 守卫清单（/api/v1|copilot|admin
    与 /v1 才守），故不发 Authorization。出口仍是 assert_local + _OPENER，不自开连接路径。"""
    req = urllib.request.Request(assert_local(BASE + path), method="GET")
    with _open(req, timeout) as r:
        return r.read().decode("utf-8", "replace")


def login(username: str, password: str | None = None) -> str:
    """P2 账号体系：/api/v1/auth/login 换 24h token。口令缺省读 DEMO_PASSWORD 环境变量。

    复用 post_json（localhost 白名单校验内嵌），不新增网络调用原语；
    登录路由不在 JwtAuthFilter 守卫清单，空 Authorization 头被服务端忽略。"""
    import os
    pw = password or os.environ.get("DEMO_PASSWORD")
    if not pw:
        raise RuntimeError("DEMO_PASSWORD 环境变量未设置（demo 账号口令只从环境读取）")
    return post_json("/api/v1/auth/login", {"username": username, "password": pw},
                     token="")["token"]


def load_tokens() -> dict:
    """兼容旧调用名的统一入口：合法账号=实时 login，红队畸形 token=读 redteam_tokens.txt。

    返回键与既有脚本一致：sre_l1/sre_l3/sre_acme + sre_l0/sre_neg/tenant_none/tenant_blank/tenant_long。
    """
    out = {name: login(user) for name, user in ACCOUNTS.items()}
    if _TOKENS.exists():
        with open(_TOKENS, encoding="utf-8") as fh:
            for line in fh:
                if "=" in line:
                    k, v = line.strip().split("=", 1)
                    out[k] = v
    return out


def post_json(path: str, body: dict, token: str, timeout: int = 60) -> dict:
    req = urllib.request.Request(
        assert_local(BASE + path), data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with _open(req, timeout) as r:
        return json.load(r)


def search_docs(query: str, mode: str, token: str) -> list[dict]:
    """非流式 /search，直接返回 results 列表。timeout=30 保持 a3/evaluate 原口径。"""
    return post_json("/api/v1/copilot/search", {"query": query, "mode": mode}, token, timeout=30)["results"]


def stream_chat(body: dict, token: str, timeout: int = 120, collect_deltas: bool = True,
                trace_id: str | None = None) -> dict:
    """SSE 原语（自举告警生产者与后续验收共用，见 ADR-0011）。

    为什么不能复用 post_json：/chat/stream 的响应是事件流——post_json 用 json.load(r) 会
    整流读完再整体解析，必然抛错；且它不设 Accept 头，内容协商可能落到非流式分支。

    返回结构固定，**非 2xx 不抛异常**（把 429/401 当成可决策的返回值而非异常，
    因为告警链路绝不能因一次限流就崩掉或转入重试风暴）：
      {status, code, meta, deltas, done, error, ttft_s, total_s}
      meta   = 服务端权威事实（fingerprint / cache_hit / degradation_level / fast_path / deduplicated /
               retrieval_ms / refused / gate——gate 是 none|clarify|refuse，说清"被哪道闸拦下"）
      done   = {ttft_ms, refs, actions, near_miss}，refs 是溯源引用（含 docId 派生的 chunkId），
               收敛与命中判据都取自这里；near_miss 只在拒答时非空（候选标识符，不带正文）
      error  = 服务端 error 帧内容（链路异常时的固定话术 + code）

    两条边界：`collect_deltas=False` 时不收集答案增量（只用 meta/done 的调用方省内存）；
    `timeout` 同时是**整体截止**——逐次读的 socket 超时挡不住"慢速滴流"永久占住连接。

    **`trace_id`（2026-10-17 加）**：调用方关联键，走请求头 `X-Trace-Id`（8–64 位
    `[A-Za-z0-9_-]`，服务端非法即 400 并留痕）。告警桥用它在审计面上把同一 incident 的
    多条告警串成一段；人工/评测脚本不传。**空值与不传同义**——服务端对空白头按"未提供"
    处理，故这里不替调用方送一个空头（空头不报错，但污染请求面且更难排查）。
    """
    headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json",
               "Accept": "text/event-stream"}
    if trace_id:
        headers["X-Trace-Id"] = trace_id
    req = urllib.request.Request(
        assert_local(BASE + "/api/v1/copilot/chat/stream"),
        data=json.dumps(body).encode("utf-8"), method="POST",
        headers=headers)
    t0 = time.time()
    deadline = t0 + timeout
    out = {"status": 0, "code": None, "meta": {}, "deltas": [], "done": {}, "error": None,
           "ttft_s": None, "total_s": 0.0, "truncated": False}
    try:
        with _open(req, timeout) as r:
            out["status"] = r.status
            event = None
            for raw in r:
                if time.time() > deadline:
                    out["truncated"] = True          # 滴流超时：显式标注，不假装是完整流
                    break
                line = raw.decode("utf-8").rstrip("\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    try:
                        data = json.loads(line[5:].strip())
                    except json.JSONDecodeError:
                        continue                       # 非 JSON 帧（如 OpenAI 面的 [DONE]）不属本原语口径
                    if event == "meta":
                        out["meta"] = data
                    elif event == "delta":
                        if out["ttft_s"] is None:
                            out["ttft_s"] = round(time.time() - t0, 3)
                        if collect_deltas:
                            out["deltas"].append(data.get("token", ""))
                    elif event == "done":
                        out["done"] = data
                    elif event == "error":
                        out["error"] = data
    except urllib.error.HTTPError as e:
        out["status"] = e.code
        try:
            out["code"] = json.loads(e.read().decode("utf-8", "ignore")).get("code")
        except Exception:
            out["code"] = None
    out["total_s"] = round(time.time() - t0, 3)
    return out


def expect_http_status(path: str, body: dict, token: str, expected: int) -> tuple[bool, int]:
    """断言端点返回指定状态码；返回 (是否符合, 实际码)。"""
    req = urllib.request.Request(
        assert_local(BASE + path), data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with _open(req, 60) as r:
            return r.status == expected, r.status
    except urllib.error.HTTPError as e:
        return e.code == expected, e.code
