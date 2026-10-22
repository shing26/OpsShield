#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Alertmanager webhook → 人类通知通道的转发器（告警的**第二条出口**）。

**为什么要有这个**：2026-10-22 运维审查指出"告警只在机器链路里闭环，人看不到"——
bridge（scripts/alert_bridge.py）把告警翻译成排障请求打回 OpsPilot，但那条链路的终点
**就是被监控的网关本身**。网关挂掉时（OpsPilotDown 正是这种时刻），bridge 一定送不出去，
链路越完整，人类越收不到通知。人这个出口必须不依赖被监控对象，故它是并列的第二条 receiver
（见 ops/alerting/alertmanager.yml 的 route.routes + continue: true）。

**为什么是独立的小脚本而不是桥的一部分**：两个出口的失败语义不同。桥的失败意味着"这次
排障请求没做成"（可重试、有配额约束、要判重）；人类通道的失败意味着"没人知道出事了"
（必须让 Alertmanager 重试，且不能因为落盘失败就吞掉通知）。把两套语义塞进一个进程，
迟早会在某次改动里让一条出口的失败标准污染另一条。

**处置口径（2026-10-22 扩展为多渠道路由）**：
- `OPSPILOT_HUMAN_CHANNEL=auto`（默认）：按 webhook URL 自动识别 Slack / 飞书自定义机器人 /
  企微机器人；识别不了则按 generic 原样转发 Alertmanager JSON（给 relay/值班平台）。
- 也可显式指定 `slack|feishu|wecom|telegram|generic`；Telegram 需
  `OPSPILOT_TELEGRAM_BOT_TOKEN` + `OPSPILOT_TELEGRAM_CHAT_ID`。
- 各 IM 的消息体由 `build_message` 生成（Slack text / 飞书 msg_type / 企微 msgtype /
  Telegram sendMessage）；generic 保持原始载荷不改写。
- 没配：追加一行 logs/human-alerts.jsonl + WARN。**不是静默丢弃**——"人类通道没接通"
  这件事本身要可查，否则部署遗漏会伪装成"没有告警"。
- 上游失败（非 2xx / 超时 / 连接失败）：回 **503**，让 Alertmanager 重试；成功回 200。
  未配置时回 200（已落盘兜底，重试无意义）。
- 载荷 1MB 上限：先看声明的 Content-Length 再决定读不读，拒收不放大内存（与 bridge 同口径）。
  超限回 413（4xx，不诱发重试）；畸形 JSON 回 400。

健康面：GET /healthz → 200，只答"本进程在听 + 渠道/目标 host（不泄露 token）"，**不打上游**
（那会把健康探针变成一次真实通知）。

用法：
  OPSPILOT_HUMAN_CHANNEL=auto OPSPILOT_HUMAN_WEBHOOK=https://hooks.slack.com/services/... python scripts/notify.py
  OPSPILOT_HUMAN_CHANNEL=telegram OPSPILOT_TELEGRAM_BOT_TOKEN=... OPSPILOT_TELEGRAM_CHAT_ID=... python scripts/notify.py
  python scripts/notify.py --port 8090            # 未配置时落 JSONL + WARN

退出码：0 正常退出 / 2 配置错误（渠道/凭据形状非法、端口占用）。
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

REPO = Path(__file__).resolve().parent.parent
LEDGER = REPO / "logs" / "human-alerts.jsonl"

DEFAULT_PORT = 8090
WEBHOOK_ENV = "OPSPILOT_HUMAN_WEBHOOK"
CHANNEL_ENV = "OPSPILOT_HUMAN_CHANNEL"
TELEGRAM_TOKEN_ENV = "OPSPILOT_TELEGRAM_BOT_TOKEN"
TELEGRAM_CHAT_ENV = "OPSPILOT_TELEGRAM_CHAT_ID"
FEISHU_SECRET_ENV = "OPSPILOT_FEISHU_SECRET"

# 渠道配置（2026-10-22 扩展）：把“人类告警”从只能 raw 转发，扩成常见 IM 的直接投递。
# 设计原则：**能自动识别的就不要求用户填 channel**——粘贴一个 Slack/飞书/企微机器人 URL
# 就能用；Telegram 必须额外给 bot token 与 chat_id（这是协议事实，不是本项目发明）。
# 飞书 App ID/Secret 不在本版支持：它还需要 tenant_access_token + 明确的 chat_id/接收人，
# 且要申请机器人权限；仅两个值不足以可靠投递。先用自定义机器人 webhook（更简单）。
SUPPORTED_CHANNELS = ("auto", "generic", "slack", "feishu", "wecom", "telegram")


@dataclass(frozen=True)
class HumanChannel:
    """一个已解析的人类通知渠道；不打印 token/webhook 完整值。"""

    kind: str = "generic"
    webhook: str = ""
    bot_token: str = ""
    chat_id: str = ""
    feishu_secret: str = ""

    def configured(self) -> bool:
        if self.kind == "telegram":
            return bool(self.bot_token and self.chat_id)
        return bool(self.webhook)

    def label(self) -> str:
        if self.kind == "telegram":
            return f"telegram(chat={self.chat_id})" if self.configured() else "未配置（仅落 JSONL）"
        if self.webhook:
            host = urlparse(self.webhook).hostname
            # 保持旧 healthz 文案对 generic 的兼容（既有测试/面板按这个字符串判断"已配置"）。
            return f"已配置(host={host})" if self.kind == "generic" else f"{self.kind}(host={host})"
        return "未配置（仅落 JSONL）"

# 上游超时：与 Alertmanager 的 webhook 重试节奏对齐——5s 足够 IM 网关回话，
# 再长就会让 Alertmanager 侧堆积在途请求（它的超时默认 10s）。
TIMEOUT_S = 5.0

# 1MB 上限：Alertmanager 的载荷远小于此。**先看声明长度再决定读不读**——
# 读完再判会让"拒收超大载荷"本身变成一次内存放大（对方能逼你收 100MB）。
MAX_BODY = 1_000_000


def summarize(payload: dict) -> dict:
    """把 Alertmanager 载荷压成可落盘/可读的摘要（只取事实，不解释）。

    alertname 去重保序：一个 group 里同名规则可能有多条（不同 instance），
    重复念名字只会让台账更难读。载荷是不可信输入，任何字段都先判类型。
    """
    alerts = payload.get("alerts")
    alerts = alerts if isinstance(alerts, list) else []
    names: list[str] = []
    for a in alerts:
        if not isinstance(a, dict):
            continue
        labels = a.get("labels") or {}
        name = labels.get("alertname") if isinstance(labels, dict) else None
        if isinstance(name, str) and name and name not in names:
            names.append(name)
    status = payload.get("status") if isinstance(payload.get("status"), str) else ""
    group_key = payload.get("groupKey") if isinstance(payload.get("groupKey"), str) else ""
    return {"status": status, "count": len(alerts), "alertnames": names, "group_key": group_key}


def format_text(payload: dict) -> str:
    """一行人类可读的摘要（台账的 text 列 / 未配置时的 WARN 用）。原样转发的载荷不走它。"""
    s = summarize(payload)
    names = "、".join(s["alertnames"]) or "（无 alertname）"
    return f"[{s['status'] or 'unknown'}] {s['count']} 条告警：{names}"


def append_ledger(payload: dict, path: Path, *, reason: str, now_ms: int | None = None) -> dict | None:
    """追加一行人类告警台账；写失败**不抛**（通知不该因为日志写不了而消失），只 WARN。

    reason 区分"为什么落到本地"：no_webhook（未配置，部署问题）才是当前唯一入口；
    留成字段是为了以后"上游失败也兜底落盘"时不用改 schema。
    """
    row = {
        "ts": now_ms if now_ms is not None else int(time.time() * 1000),
        **summarize(payload),
        "reason": reason,
        "text": format_text(payload),
    }
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    except OSError as e:
        print(f"[warn] 人类告警台账写入失败（不阻断告警）: {e}", file=sys.stderr, flush=True)
        return None
    return row


def _post_json_raw(url: str, body: object,
                   timeout: float) -> tuple[bool, int | None, str, object]:
    """POST JSON → (是否成功, 上游状态码, 失败摘要, 解析后的响应体)。失败不抛。"""
    raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=raw, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = resp.read()
            parsed = None
            try:
                parsed = json.loads(data.decode("utf-8")) if data else None
            except (UnicodeDecodeError, json.JSONDecodeError):
                parsed = None
            return True, getattr(resp, "status", 200), "", parsed
    except urllib.error.HTTPError as e:
        # 非 2xx 是"上游收到了但拒了"：仍按失败回 503（重试可能仍有意义，由归属方决定）
        return False, e.code, f"HTTP {e.code}", None
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        return False, None, f"{type(e).__name__}: {e}", None


def _post_json(url: str, body: object, timeout: float) -> tuple[bool, int | None, str]:
    ok, code, detail, _ = _post_json_raw(url, body, timeout)
    return ok, code, detail


def forward(payload: dict, webhook: str, timeout: float = TIMEOUT_S) -> tuple[bool, int | None, str]:
    """POST 原始载荷到人类 webhook（generic/relay 形态）→ (是否成功, 上游状态码, 失败摘要)。

    **原样转发**：载荷是 Alertmanager 的契约，下游按它自己的格式解析；在这里改写文本
    等于把"通知格式"变成第二个要维护的契约。失败不抛：通知器把一次失败当数据
    （回 503 让上游重试），不是当异常向上冒。
    """
    return _post_json(webhook, payload, timeout)


def build_message(payload: dict, channel: HumanChannel) -> object:
    """按渠道生成最小可读消息；generic 保持 Alertmanager 原始载荷。"""
    text = format_text(payload)
    if channel.kind == "slack":
        return {"text": text}
    if channel.kind == "feishu":
        body = {"msg_type": "text", "content": {"text": text}}
        if channel.feishu_secret:
            # 飞书自定义机器人开启“签名校验”后的协议：timestamp + "\n" + secret 作为
            # HmacSHA256 的 key，对空串签名，再 Base64。缺 secret 时飞书会回 HTTP 200 + code=19021，
            # 所以 forward_channel 还必须检查响应体，不能只看 HTTP 状态。
            ts = str(int(time.time()))
            key = f"{ts}\n{channel.feishu_secret}".encode("utf-8")
            sign = base64.b64encode(hmac.new(key, b"", hashlib.sha256).digest()).decode("ascii")
            body.update({"timestamp": ts, "sign": sign})
        return body
    if channel.kind == "wecom":
        return {"msgtype": "text", "text": {"content": text}}
    if channel.kind == "telegram":
        return {"chat_id": channel.chat_id, "text": text, "disable_web_page_preview": True}
    return payload


def forward_channel(payload: dict, channel: HumanChannel,
                    timeout: float = TIMEOUT_S) -> tuple[bool, int | None, str]:
    """按渠道投递；失败摘要**不包含 token/完整 URL**（避免凭据随告警日志扩散）。"""
    if channel.kind == "telegram":
        # Bot API 的 token 在路径里；任何异常摘要都只回固定文案，不回显 URL。
        ok, code, _, body = _post_json_raw(
            f"https://api.telegram.org/bot{channel.bot_token}/sendMessage",
            build_message(payload, channel), timeout)
        if not ok:
            return False, code, "Telegram API 调用失败（详见 notify 日志）"
        if isinstance(body, dict) and body.get("ok") is False:
            return False, code, "Telegram API 返回 ok=false（详见 notify 日志）"
        return True, code, ""
    ok, code, detail, body = _post_json_raw(channel.webhook, build_message(payload, channel), timeout)
    if not ok:
        return False, code, detail
    # IM 平台常见“HTTP 200 + 业务错误码”：必须看响应体，否则会假绿。
    if channel.kind == "feishu" and isinstance(body, dict):
        biz = body.get("code")
        if biz not in (0, None):
            return False, code, f"feishu code={biz} msg={str(body.get('msg', ''))[:120]}"
    if channel.kind == "wecom" and isinstance(body, dict):
        biz = body.get("errcode")
        if biz not in (0, None):
            return False, code, f"wecom errcode={biz} errmsg={str(body.get('errmsg', ''))[:120]}"
    if channel.kind == "slack" and isinstance(body, dict) and body.get("ok") is False:
        return False, code, "slack ok=false"
    return True, code, ""


def handle_payload(
    payload: dict,
    *,
    webhook: str = "",
    channel: HumanChannel | None = None,
    ledger_path: Path,
    forwarder=None,
    timeout: float = TIMEOUT_S,
) -> tuple[int, dict]:
    """一次 webhook 投递的完整处置 → (HTTP 状态码, 响应体)。

    状态码语义是这里**唯一**的对外契约：503 = 没送达、值得重试；其余都是"已处置"。
    未配置通道也是"已处置"（落 JSONL 兜底），回 200——重试不会让配置凭空出现。
    forwarder 参数保留给单测注入；生产走 forward_channel（按渠道生成消息）。
    """
    ch = channel or HumanChannel(kind="generic", webhook=webhook)
    if not ch.configured():
        append_ledger(payload, ledger_path, reason="no_webhook")
        print(f"[warn] 未配置人类通知渠道（{WEBHOOK_ENV}/{CHANNEL_ENV}）："
              f"人类告警仅落 {ledger_path}（配置后请重启本进程）", file=sys.stderr, flush=True)
        return 200, {"status": "recorded", "reason": "no_webhook", "text": format_text(payload)}
    if forwarder is not None:
        ok, code, detail = forwarder(payload, ch.webhook, timeout)
    else:
        ok, code, detail = forward_channel(payload, ch, timeout)
    if ok:
        return 200, {"status": "forwarded", "channel": ch.kind, "upstream": code}
    return 503, {"status": "upstream_failed", "channel": ch.kind,
                 "upstream": code, "error": detail}


def webhook_from_env(env=None) -> str:
    """从进程环境取人类 webhook（显式 env 优先；.env 由 main 先注入环境，不在这里读文件）。"""
    e = os.environ if env is None else env
    return (e.get(WEBHOOK_ENV) or "").strip()


def _detect_kind(webhook: str) -> str:
    """按 URL 形状识别常见机器人；识别不了就是 generic（原样转发）。"""
    u = urlparse(webhook)
    host = (u.hostname or "").lower()
    path = u.path or ""
    if host == "hooks.slack.com" or host.endswith(".slack.com"):
        return "slack"
    if host == "open.feishu.cn" and "/bot/v2/hook" in path:
        return "feishu"
    if host == "qyapi.weixin.qq.com" and "/cgi-bin/webhook" in path:
        return "wecom"
    return "generic"


def channel_from_env(env=None) -> HumanChannel:
    """解析人类通知渠道；能自动识别 URL 时不要求用户填 channel。

    优先级：显式 OPSPILOT_HUMAN_CHANNEL > Telegram 凭据 > URL 形状 > generic。
    """
    e = os.environ if env is None else env
    webhook = (e.get(WEBHOOK_ENV) or "").strip()
    token = (e.get(TELEGRAM_TOKEN_ENV) or "").strip()
    chat = (e.get(TELEGRAM_CHAT_ENV) or "").strip()
    feishu_secret = (e.get(FEISHU_SECRET_ENV) or "").strip()
    requested = (e.get(CHANNEL_ENV) or "auto").strip().lower()
    if requested not in SUPPORTED_CHANNELS:
        raise ValueError(f"{CHANNEL_ENV} 只接受 {'|'.join(SUPPORTED_CHANNELS)}，收到: {requested}")
    if requested == "auto":
        kind = "telegram" if token and chat else _detect_kind(webhook)
    else:
        kind = requested
    ch = HumanChannel(kind=kind, webhook=webhook, bot_token=token, chat_id=chat,
                      feishu_secret=feishu_secret)
    validate_channel(ch)
    return ch


def validate_channel(channel: HumanChannel) -> None:
    if channel.kind == "telegram":
        if not channel.bot_token or not channel.chat_id:
            raise ValueError(f"telegram 需要 {TELEGRAM_TOKEN_ENV} 与 {TELEGRAM_CHAT_ENV}")
        return
    validate_webhook(channel.webhook)


def validate_webhook(webhook: str) -> None:
    """空值合法（走 JSONL 兜底）；非空则必须是无 userinfo 的 http(s) URL。

    拒绝 userinfo 的理由与 bridge 的 assert_local 同族：凭据塞进 URL 会随异常/日志
    一起被打印出去，而人类 webhook 的鉴权本来就该走 query token 或下游自身配置。
    """
    if not webhook:
        return
    u = urlparse(webhook)
    if u.scheme not in ("http", "https") or not u.hostname or u.username or u.password:
        raise ValueError(f"{WEBHOOK_ENV} 必须是无 userinfo 的 http(s) URL（当前形状非法）")


def _as_channel(value) -> HumanChannel:
    return value if isinstance(value, HumanChannel) else HumanChannel(kind="generic", webhook=value)


def _channel_label(channel) -> str:
    """启动/健康面用：只报渠道与 host/chat，**不打印 token 或完整 URL**。"""
    return _as_channel(channel).label()


def _json_body(handler: BaseHTTPRequestHandler, code: int, body: dict) -> None:
    raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(raw)))
    handler.end_headers()
    handler.wfile.write(raw)


def _handler_for(channel, ledger_path: Path, forwarder=None):
    ch = _as_channel(channel)
    class Handler(BaseHTTPRequestHandler):
        server_version = "OpsPilotHumanNotify/1"

        def _json(self, code: int, body: dict) -> None:
            _json_body(self, code, body)

        def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler 的接口名
            # 只读存活面：不向上游发探测——那会把健康检查变成一次真实通知。
            if self.path.split("?")[0] in ("/healthz", "/"):
                self._json(200, {"status": "UP", "service": "notify",
                                 "human_channel": _channel_label(ch)})
            else:
                self._json(404, {"code": "NOT_FOUND", "message": "only /healthz"})

        def do_POST(self):  # noqa: N802
            if self.path.split("?")[0] != "/api/v2/alerts":
                self._json(404, {"code": "NOT_FOUND", "message": "only /api/v2/alerts"})
                return
            try:
                n = int(self.headers.get("Content-Length") or 0)
                if n > MAX_BODY:
                    self.close_connection = True     # 不读体，直接关；对方写剩余字节时会拿到重置
                    self._json(413, {"code": "PAYLOAD_TOO_LARGE",
                                     "message": f"payload size {n} 超限"})
                    return
                if n <= 0:
                    raise ValueError(f"payload size {n}")
                payload = json.loads(self.rfile.read(n).decode("utf-8"))
                if not isinstance(payload, dict):
                    raise ValueError("payload 必须是 JSON 对象")
            except Exception as e:  # noqa: BLE001 - 畸形输入回 400，不 5xx（重试不会让它变好）
                self._json(400, {"code": "BAD_PAYLOAD", "message": str(e)[:200]})
                return
            try:
                code, body = handle_payload(payload, channel=ch, ledger_path=ledger_path,
                                            forwarder=forwarder)
            except Exception as e:  # noqa: BLE001 - 处置异常不得打死服务
                print(f"[warn] 处置失败: {type(e).__name__}: {e}", file=sys.stderr, flush=True)
                self._json(500, {"code": "INTERNAL", "message": str(e)[:200]})
                return
            self._json(code, body)

        def log_message(self, fmt, *args):  # 静音缺省逐请求日志（台账才是记录面）
            pass

    return Handler


def serve(channel, ledger_path: Path, port: int) -> int:
    ch = _as_channel(channel)
    httpd = ThreadingHTTPServer(("127.0.0.1", port), _handler_for(ch, ledger_path))
    if port == 0:                                     # 用例形态：取随机端口
        port = httpd.server_address[1]
    print(f"[notify] 监听 http://127.0.0.1:{port}/api/v2/alerts "
          f"人类通道={_channel_label(ch)} 台账={ledger_path}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[notify] 停止", flush=True)
    finally:
        httpd.server_close()
    return 0


def _load_dotenv() -> None:
    """把仓库根的 .env 装进进程环境（不覆盖已有值）。

    为什么复用 bridge 的读取器而不是再写一份：KEY=VALUE 的解析约定（引号、注释、不展开、
    不执行）只该在**一处**定义——两份实现迟早漂移，而漂移的那天恰好是某个人在改凭据入口。
    这里只在 __main__ 路径上做一次局部 import，避免模块被测试导入时拉起桥的整套依赖。
    """
    sys.path.insert(0, str(REPO / "scripts"))
    try:
        from alert_bridge import load_env_file
    except Exception as e:  # noqa: BLE001 - 读不到 .env 约定不该让通知器起不来
        print(f"[warn] 无法加载 .env 读取器（{type(e).__name__}: {e}），仅用进程环境", file=sys.stderr)
        return
    load_env_file(REPO / ".env")


def main(argv: list[str] | None = None) -> int:
    # 先读 .env：--port 的默认值与 webhook 都来自环境，晚读就吃不到。
    _load_dotenv()
    ap = argparse.ArgumentParser(description="Alertmanager webhook → 人类通知通道转发器")
    ap.add_argument("--port", type=int, default=int(os.environ.get("OPSPILOT_NOTIFY_PORT", DEFAULT_PORT)),
                    help=f"监听端口（默认读 OPSPILOT_NOTIFY_PORT 或 {DEFAULT_PORT}）")
    ap.add_argument("--ledger", default=str(LEDGER),
                    help="未配置 webhook 时的落盘路径（默认 logs/human-alerts.jsonl）")
    a = ap.parse_args(argv)
    try:
        channel = channel_from_env()
    except ValueError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        return 2
    try:
        return serve(channel, Path(a.ledger), a.port)
    except OSError as e:
        print(f"FAIL: 无法监听 127.0.0.1:{a.port}：{e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
