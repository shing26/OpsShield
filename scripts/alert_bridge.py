#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Alertmanager webhook → OpsPilot 排障链路的桥（外部监控 adapter 的第一个真实输入端）。

**为什么要有这个**：本仓此前的"真实告警"全部来自自产观测面（ADR-0011 的自举生产者、
`watchdog_alerts.py` 的 Docker 状态翻转）。它们的输入是真的，但**来源是本机自己的运行态**——
系统至今没有消费过任何一份由它管不着的组件发出来的告警。Alertmanager 的 webhook 补的正是
这一段：告警由 Prometheus 规则产生、由 Alertmanager 投递，本脚本只负责翻译，不产生判断。

**为什么是独立脚本而不是网关端点**：网关的 JWT 是给人（和 agent）用的身份凭证，Alertmanager
不是一个账号。给它开一个不带 JWT 的面，等于在 ADR-0008"每条共享路径都有权限维度"之外开第四个
口子；给它造一个账号，则要先发明"机器调用者的配额归属"这一整套语义。本轮的目的是**观测**
（指纹在真实告警标签下会碎成什么样），不是上新能力，所以桥停在宿主的客户端形态：
它复用与人工提问完全相同的编排入口，产品面零改动。触发线兑现的判据与后续提升为端点的决策，
见 OPS §5.1。

**摊平口径（每条 alert → 独立请求）**：Alertmanager 按 group_by 把 N 条告警打包进一个 POST。
如果拼成一条 query，N 条会被压成一个指纹——那测的是本脚本拼字符串的手艺，不是 Alertmanager 的
真实行为。逐条转发保留了"哪些告警天然同指纹、哪些被归一化意外抹平"这个观测面，那正是本轮要的东西。

**不判重**：firing 与 resolved 都照转。Alertmanager 是纠偏系统，同一条告警在 repeat_interval 内
会反复重发，这些重复**必须真的打到网关**——否则看不到 L1 命中与 SingleFlight 在真实输入上的表现。
防自伤靠三件事：小时上限、配额保留线、单实例锁（复用 alert_producer.py 已确立的口径），
而不是内容判重。

**incident 关联（2026-10-17 第二轮）**：Alertmanager 按 `group_by` 把 N 条告警打包成一个 group——
**那就是上游对"这是一次事故"的定义**，本桥不重新发明这层判断。它把该 group 的身份（`groupKey`
原文 + group 内最早的 `startsAt`）派生成一个不透明 id `am-<32 位十六进制>`，随 `X-Trace-Id`
请求头发给网关，于是同一次事故的多条告警在审计面共用同一列 `trace_id`。
**只关联、不归并**：摊平口径不变，N 条告警仍是 N 个独立请求、N 个独立答案；被串起来的只是
"读的时候能不能一次读出来"。为什么拿 `min(startsAt)` 补时间维：`groupKey` 是**路由桶 id**、
不含时间——同一条规则下周再 fire 会拿到同一个桶，没有这层锚点，两次事故会被并成一次。

**规则作者契约**：`labels.error_code` 是可选的精确符号。注解（summary/description）写自然语言，
错误码写在标签里——本脚本**只拼接、不发明**。标签缺失就转发没有错误码的文本，让系统走语义路；
那是被设计的分支（规则 opspilot.yml 里签核阈值那条就是故意的），不是错误。

用法：
  python scripts/alert_bridge.py                       # 常驻，默认监听 127.0.0.1:8089
  python scripts/alert_bridge.py --port 8089 --env local
  python scripts/alert_bridge.py --max-per-hour 20 --quota-reserve 50
  python scripts/alert_bridge.py --dry-run             # 只解析与留痕，不打网关

健康面：GET /healthz → 200。读它就说明桥在听；它不查询网关（那会把健康面变成一次真实调用）。

退出码：0 正常退出 / 2 主体权限不足（403，重试无意义）/ 3 已有一个实例在跑。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "offline"))
import localapi  # noqa: E402  # 单一 HTTP/token 事实源（含 assert_local SSRF 防线）
from alert_delivery import (  # noqa: E402
    AlertDelivery,
    AlertSession,
    BRIDGE_GATES,
    DeliveryAttempt,
    DeliveryPolicy,
    HourAccounting,
    LoginFailureMode,
    TransportFailureHandling,
)

LEDGER = REPO / "logs" / "alert-bridge.jsonl"
LOCK = REPO / "logs" / ".alert-bridge.lock"

# 配额保留线：告警不得把自己烧穿——否则随后的人工排障会被 429 挡掉。
# 口径与 alert_producer.py 一致：配额按 sub 计数，本桥复用告警主体 sre-watcher，
# 所以这条线保护的是告警主体自己的余量。resolved 同样计配额（它是真实请求，不豁免）。
QUOTA_RESERVE = 50

# 登录面连续失败上限：口令错/主体被锁时重试只会把 15 分钟锁定刷成常锁
# （AuthService 5 次失败锁 15 分钟），且每次失败都写一行鉴权审计——故退避 + 上限后退出。
MAX_LOGIN_FAILURES = 3

# 单组摊平上限：一条 group 里有 N 条 alert 就是 N 次 LLM 请求。上限防的是
# "一次规则抖动把配额打光"，超出部分**记台账后丢弃**（不静默）。
GROUP_CAP = 20

# 注解/标签进 query 前的白名单。与 alert_producer.DETAIL_SAFE 同口径：载荷是**不可信输入**
# （Alertmanager 的模板可以引用任意 label，label 的值来自被抓目标的元数据），
# 故去控制字符、限长、只留安全字符集。刻意不含 < > ` $ 与引号：告警文本不需要它们，
# 而它们正是拼接/注入类写法的原料。
TEXT_SAFE = re.compile(r"[^\w\s:.,;/()\[\]=@+\-]")
SUMMARY_MAX = 200
QUERY_MAX = 600
ERROR_CODE_MAX = 64


def _clean(v, cap: int) -> str:
    """把不可信文本洗成可安全进 query/审计的短文本（限长 + 字符白名单）。"""
    s = TEXT_SAFE.sub(" ", str(v if v is not None else ""))
    return " ".join(s.split())[:cap]


def load_env_file(path: Path) -> None:
    """把仓库根的 `.env` 读进进程环境，**不覆盖已存在的变量**（显式 env 优先）。

    为什么脚本自己读：OPS §13 给桥的起法是 `set -a && . ./.env`（bash 语法），而本机
    与多数 Windows 环境没有可用的 bash——桥要么在起法上依赖 shell 方言，要么自己认这份
    约定。选后者：`.env` 是本仓唯一凭据来源（`seed_demo_users` / `start_gateway.sh` 都从它取），
    桥只是补上同一个入口。`setdefault` 保证显式导出的 env 仍然赢。

    解析只认 `KEY=VALUE`（值可带成对引号）：注释/空行/无 `=` 行跳过，不展开变量、不执行
    命令——它是配置读取，不是 shell。**注意** `offline/localapi.py` 的 `BASE` 在 import 期
    读取 `OPSPILOT_BASE`，故这个键要靠 shell 导出（`.env` 里也不放它，无实际影响）。
    """
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return
    for line in lines:
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        key, _, value = s.partition("=")
        key, value = key.strip(), value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if key:
            os.environ.setdefault(key, value)


def alert_label(alert: dict, key: str, default: str = "") -> str:
    """Alertmanager 的 labels 是扁平 string→string；缺失/非字符串一律按缺省处理。"""
    labels = alert.get("labels") or {}
    v = labels.get(key)
    return v if isinstance(v, str) else default


def alert_annotation(alert: dict, *keys: str) -> str:
    """按序取第一个非空注解（summary 优先于 description）。"""
    ann = alert.get("annotations") or {}
    for k in keys:
        v = ann.get(k)
        if isinstance(v, str) and v.strip():
            return v
    return ""


def compose_query(alert: dict) -> str:
    """query 合成：注解正文 + 错误码标签。**只拼接，不发明。**

    错误码缺失时**不得**回退到 alertname 或指标名去凑——那些不是错误码，凑出来的形状
    会被 EsSearchService 的 ERROR_CODE 判据当成精确符号走快路径，等于伪造一个强标识符。
    缺失就走语义路，这是契约的一部分。
    """
    body = _clean(alert_annotation(alert, "summary", "description"), SUMMARY_MAX)
    code = _clean(alert_label(alert, "error_code"), ERROR_CODE_MAX)
    if body and code:
        q = f"{body} {code}"
    else:
        q = body or code
    return q[:QUERY_MAX]


def derive_alert_id(alertname: str, job: str, error_code: str, status: str) -> str:
    """告警身份（不透明，交给服务端）：由规则身份四元组派生，**与 message 文本无关**。

    为什么不是拿 query 再哈希一次：query 是给人读的文案，规则模板会把 `{{ $value }}`
    渲染进去，同一条告警每次评估都是新文本（2026-10-17 首次实测，见 OPS §13 与台账 §3.1）。
    真正稳定的是"哪条规则、哪个 job、哪个错误码、firing 还是 resolved"——这正是 Alertmanager
    聚合时用的身份维度。服务端拿它当**不透明身份**再哈希、不解析；桥在这里把它定长成 32 位
    十六进制，既满足 ChatRequest 的 8–128 位形状约束，也让台账里的 `alert_id` 可直接对账
    （哈希前的原始四元组另记 `alert_id_basis` 一列）。

    status 参与身份是刻意的：firing 与 resolved 是两种语义，不该互相命中缓存。
    """
    basis = "|".join([alertname, job, error_code, status])
    return hashlib.sha256(basis.encode("utf-8")).hexdigest()[:32]


def derive_incident_id(group_key: str, min_starts_at: str) -> str:
    """告警 incident 身份（不透明，交给服务端当**关联键**）：由上游分组 + 本次事故起点派生。

    为什么哈希 `groupKey` 原文而**不解析**它：`{}:{alertname="X", job="y"}` 这种形状是
    Alertmanager 的实现细节，解析它就跟版本绑定；哈希它对格式变化免疫。

    为什么还要 `min_starts_at`：`groupKey` 是**路由桶 id，不是 incident id**——它没有时间维，
    同一条规则下周再 fire 会拿到同一个桶，直接当 incident id 会把两次事故并成一次。
    组内最早的 `startsAt` 恰好是"这一次事故的起点"：整个 group 每次重发都带齐全体成员，
    故它在组生命周期内稳定，组解散后重建（成员全部 resolved 再 fire）自然换新。

    `am-` 前缀是**自描述**用的：审计行 `trace_id` 这一列平时装的是人写的调用链 id
    （`agent-run-7f3a` 那种），一串裸十六进制会让人不知道它从哪来。

    形状与 `alert_id` 同族（定长十六进制），并额外满足 `X-Trace-Id` 的
    `[A-Za-z0-9_-]{8,64}`——`am-` + 32 位十六进制 = 35 字符，桥**不可能**送出一个 400。
    """
    basis = f"{group_key}|{min_starts_at}"
    return "am-" + hashlib.sha256(basis.encode("utf-8")).hexdigest()[:32]


def _group_key_of(payload: dict) -> str:
    """顶层 groupKey（Alertmanager 契约保证 string；载荷是不可信输入，故仍判类型）。"""
    gk = payload.get("groupKey")
    return gk if isinstance(gk, str) else ""


def _min_starts_at(alerts) -> str:
    """该 group 内最早的 `startsAt`（RFC3339 UTC 字面量，同格式下字典序即时序）。

    **必须对完整 payload 算，不能对 flatten 截断后的头部算**：`GROUP_CAP` 一触发，
    本批带了几条就会改变 incident 身份——那是会**静默**漂的失效方式。故本函数拿的是
    `payload["alerts"]` 整体，与交给网关的那一截无关。

    已知边界（如实登记）：上游若混用时间戳格式（如带 `+08:00` 偏移），字典序不再等于时序。
    Alertmanager 的 webhook 恒定输出同格式的 UTC 字面量，故此处不引入时间库解析。
    """
    if not isinstance(alerts, list):
        return ""
    vals = [a["startsAt"].strip() for a in alerts
            if isinstance(a, dict) and isinstance(a.get("startsAt"), str) and a["startsAt"].strip()]
    return min(vals) if vals else ""


def incident_of(payload: dict) -> tuple[str, str]:
    """(incident_id, incident_basis)：**一处算**，摊平行与丢弃行共用，避免三处各算一次迟早漂。"""
    group_key = _group_key_of(payload)
    anchor = _min_starts_at(payload.get("alerts"))
    return derive_incident_id(group_key, anchor), f"{group_key}|{anchor}"


def to_request(alert: dict, env: str, alert_id: str) -> dict:
    """一条 Alertmanager alert → 一次 ChatRequest。

    service 取 `job`（scrape config 里自己写的稳定标识），**不取 instance**：
    instance 形如 `localhost:8081`，既带端口会被 `<N>` 归一化，又随重启端口变化——
    那正是本轮要观测的"碎点"之一，不该在映射层先把它当成服务身份固化下来。

    `alert_id` 由调用方（`flatten`）从四元组派生后传入，不在这里重算：请求体、台账、
    验收判据必须引用同一个值，三处各算一次迟早会漂。

    **线格式字段名是 `alertId`（camelCase），不是 `alert_id`**：`ChatRequest` 是 Jackson
    绑定的请求体，走默认命名策略。发 snake_case 不会报错，字段会被静默忽略、身份退回文本
    指纹——2026-10-17 live 验收实测踩到（`fp` 在同一身份下仍变化，见 qa §6）。台账里仍是
    `alert_id`，那是桥自己的落盘约定，与 HTTP 契约无关。
    """
    return {
        "query": compose_query(alert),
        "source": "alert",
        "service": _clean(alert_label(alert, "job"), 64),
        "env": env,
        "alertId": alert_id,
    }


def flatten(payload: dict, env: str, cap: int = GROUP_CAP) -> tuple[list[dict], int]:
    """Alertmanager payload → (逐条请求, 被上限丢弃的条数)。

    payload 形状由 Alertmanager 定义（它不是我们的输入契约，是我们要适配的外部契约）：
    顶层 status/groupKey/commonLabels/commonAnnotations，逐条在 alerts[]。
    非 dict 的 alert 元素**跳过并计入丢弃**，不抛异常——告警链路的失效方式不该是 500。

    每条 item 除告警身份外还带**同组共享**的 `incident_id`/`incident_basis`（见 `incident_of`）：
    它是 group 级的，故先算一次再分发给本组每条——不是每条 alert 各派生一次。
    """
    alerts = payload.get("alerts")
    if not isinstance(alerts, list):
        return [], 0
    group_key = _group_key_of(payload)
    group_status = payload.get("status") if isinstance(payload.get("status"), str) else ""
    incident_id, incident_basis = incident_of(payload)
    out: list[dict] = []
    dropped = 0
    for a in alerts:
        if not isinstance(a, dict):
            dropped += 1
            continue
        if len(out) >= cap:
            dropped += 1
            continue
        alertname = _clean(alert_label(a, "alertname"), 64)
        job = _clean(alert_label(a, "job"), 64)
        error_code = _clean(alert_label(a, "error_code"), ERROR_CODE_MAX)
        status = _clean(a.get("status") if isinstance(a.get("status"), str) else group_status, 32)
        alert_id = derive_alert_id(alertname, job, error_code, status)
        out.append({
            "group_key": group_key,
            "group_status": group_status,
            "status": status,
            "alertname": alertname,
            "job": job,
            "error_code": error_code,
            "alert_id": alert_id,
            "alert_id_basis": f"{alertname}|{job}|{error_code}|{status}",
            "incident_id": incident_id,
            "incident_basis": incident_basis,
            "request": to_request(a, env, alert_id),
        })
    return out, dropped


class Bridge:
    """HTTP 收报 → 翻译 → 打网关 → 落台账。单请求失败绝不让进程死。"""

    def __init__(self, args):
        self.user = args.user
        self.env = args.env
        self.dry_run = args.dry_run
        self.max_per_hour = args.max_per_hour
        self.quota_reserve = args.quota_reserve
        self.group_cap = args.group_cap
        self.stats = {"received": 0, "forwarded": 0, "skipped": 0, "errors": 0}
        self.delivery = AlertDelivery(
            AlertSession(
                self.user,
                api=localapi,
                login_failure_mode=LoginFailureMode.AUTH_STATUSES,
            ),
            ledger_path=LEDGER,
        )

    # ---- 与网关交互 -------------------------------------------------------
    @property
    def token(self) -> str:
        return self.delivery.session.token

    @token.setter
    def token(self, value: str) -> None:
        self.delivery.session.token = value

    def forward(self, item: dict) -> dict:
        """发一条；台账记录带服务端权威事实（fingerprint/cache_hit/refs）。

        `incident_id` 经 `X-Trace-Id` 头送出去（不进请求体：trace 是**关联键**，落在审计行，
        没有让 DTO 多一个字段的必要）。头、台账、验收判据三处引用的是 `item` 里同一个值。
        """
        req = item["request"]
        base = {"group_key": item["group_key"], "status": item["status"],
                "alertname": item["alertname"], "job": item["job"],
                "error_code": item["error_code"], "query": req["query"],
                "alert_id": item["alert_id"], "alert_id_basis": item["alert_id_basis"],
                "incident_id": item["incident_id"], "incident_basis": item["incident_basis"]}
        policy = DeliveryPolicy(
            gates=BRIDGE_GATES,
            max_per_hour=self.max_per_hour,
            quota_reserve=self.quota_reserve,
            hour_accounting=HourAccounting.ATTEMPT,
            login_before_send=True,
            transport_failure_handling=TransportFailureHandling.CONTINUE,
            response_code_key="error_code_resp",
            dry_run=self.dry_run,
        )
        outcome = self.delivery.deliver(
            DeliveryAttempt(
                body=req,
                ledger=base,
                trace_id=item["incident_id"],
                collect_deltas=False,
            ),
            policy,
        )
        if outcome.record.get("sent_ok") is False and "error" in outcome.record:
            self.stats["errors"] += 1
        return outcome.record

    def handle_payload(self, payload: dict) -> dict:
        """一次 webhook 投递的完整处置；返回给 Alertmanager 的响应体（永远 200 语义）。"""
        items, dropped = flatten(payload, self.env, self.group_cap)
        self.stats["received"] += len(items) + dropped
        incident_id, incident_basis = incident_of(payload)
        records = []
        if dropped:
            # **必须落台账**：丢弃是这个桥唯一会"少发告警"的路径，
            # 不落痕的话"为什么这条告警没进系统"就永远答不上来（而它偏偏发生在高负载时刻）。
            # 丢弃行也带 incident 身份：它是 group 级的，正好回答"这次事故少发了 N 条"。
            rec = {"group_key": _group_key_of(payload),
                   "status": payload.get("status") if isinstance(payload.get("status"), str) else "",
                   "incident_id": incident_id, "incident_basis": incident_basis,
                   "emit": False, "skip": "group_cap", "dropped": dropped,
                   "note": f"单组超过 {self.group_cap} 条，超出部分丢弃并留痕"}
            self.ledger(rec)
            records.append(rec)
        for item in items:
            rec = self.forward(item)
            if rec.get("emit"):
                self.stats["forwarded"] += 1
            else:
                self.stats["skipped"] += 1
            records.append(rec)
        return {"received": len(items) + dropped, "dropped": dropped, "records": len(records)}

    def ledger(self, rec: dict) -> None:
        self.delivery.ledger.append(rec)

    # ---- 单实例守卫 -------------------------------------------------------
    def _touch_lock(self) -> None:
        try:
            LOCK.write_text(str(os.getpid()), encoding="utf-8")
        except OSError:
            pass


def _handler_for(bridge: Bridge):
    class Handler(BaseHTTPRequestHandler):
        server_version = "OpsPilotAlertBridge/1"

        def _json(self, code: int, body: dict) -> None:
            raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler 的接口名
            # 只读存活面：不查网关——那会把健康探针变成一次真实调用，
            # 让"桥是否在听"和"网关是否活着"两件事纠缠在一起。
            if self.path.split("?")[0] in ("/healthz", "/"):
                self._json(200, {"status": "UP", "service": "alert-bridge",
                                 "stats": bridge.stats})
            else:
                self._json(404, {"code": "NOT_FOUND", "message": "only /healthz"})

        def do_POST(self):  # noqa: N802
            if self.path.split("?")[0] != "/api/v2/alerts":
                self._json(404, {"code": "NOT_FOUND", "message": "only /api/v2/alerts"})
                return
            try:
                n = int(self.headers.get("Content-Length") or 0)
                # 1MB 上限：Alertmanager 载荷远小于此。**先看声明长度再决定读不读**——
                # 读完再判会让"拒收超大载荷"本身变成一次内存放大（对方能逼你收 100MB）。
                if n > 1_000_000:
                    self.close_connection = True     # 不读体，直接关；客户端写剩余字节时会拿到重置
                    self._json(400, {"code": "BAD_PAYLOAD", "message": f"payload size {n} 超限"})
                    return
                if n <= 0:
                    raise ValueError(f"payload size {n}")
                payload = json.loads(self.rfile.read(n).decode("utf-8"))
                if not isinstance(payload, dict):
                    raise ValueError("payload 必须是 JSON 对象")
            except Exception as e:  # noqa: BLE001 - 畸形输入回 400，不 5xx（5xx 会诱发重试风暴）
                bridge.stats["errors"] += 1
                self._json(400, {"code": "BAD_PAYLOAD", "message": str(e)[:200]})
                return
            bridge._touch_lock()
            try:
                out = bridge.handle_payload(payload)
            except Exception as e:  # noqa: BLE001 - 处置异常不得打死服务
                bridge.stats["errors"] += 1
                print(f"[warn] 处置失败: {type(e).__name__}: {e}", file=sys.stderr)
                self._json(500, {"code": "INTERNAL", "message": str(e)[:200]})
                return
            self._json(200, out)

        def log_message(self, fmt, *args):  # 静音缺省逐请求日志（台账才是记录面）
            pass

    return Handler


def _acquire_lock(force: bool) -> bool:
    """单实例守门：小时预算与配额读数是**进程内**状态，跑两个实例 = 双倍速率。

    锁按 mtime 判活（存活实例每次投递触碰），僵死实例留下的锁 120s 后视为过期。
    锁不可用（无写权限等）时不阻断——可用性优先于互斥，与 alert_producer 同一取舍。
    """
    try:
        if LOCK.exists() and not force:
            if time.time() - LOCK.stat().st_mtime < 120:
                return False
        LOCK.parent.mkdir(parents=True, exist_ok=True)
        LOCK.write_text(str(os.getpid()), encoding="utf-8")
        return True
    except OSError:
        return True


def _release_lock() -> None:
    try:
        LOCK.unlink()
    except OSError:
        pass


def serve(bridge: Bridge, port: int) -> int:
    httpd = ThreadingHTTPServer(("127.0.0.1", port), _handler_for(bridge))
    if port == 0:                                     # 用例形态：取随机端口
        port = httpd.server_address[1]
    print(f"[bridge] 监听 http://127.0.0.1:{port}/api/v2/alerts 主体={bridge.user} "
          f"env={bridge.env} dry_run={bridge.dry_run} 上限={bridge.max_per_hour}/h "
          f"保留线={bridge.quota_reserve}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[bridge] 停止", flush=True)
    finally:
        httpd.server_close()
    return 0


def main(argv: list[str] | None = None) -> int:
    # 先读 .env：--port/--env 的默认值在 add_argument 时就求值，晚了这些键就吃不到。
    load_env_file(REPO / ".env")
    ap = argparse.ArgumentParser(description="Alertmanager webhook → OpsPilot 排障链路")
    ap.add_argument("--port", type=int, default=int(os.environ.get("ALERT_BRIDGE_PORT", "8089")),
                    help="监听端口（默认读 ALERT_BRIDGE_PORT 或 8089；0=随机）")
    ap.add_argument("--env", default=os.environ.get("ALERT_BRIDGE_ENV", "local"),
                    help="转发进 ChatRequest 的 env 维度（默认 local）")
    ap.add_argument("--user", default="sre-watcher", help="告警主体（默认 sre-watcher）")
    ap.add_argument("--max-per-hour", type=int, default=20, help="每小时转发上限（默认 20）")
    ap.add_argument("--quota-reserve", type=int, default=QUOTA_RESERVE,
                    help=f"告警主体配额保留线（默认 {QUOTA_RESERVE}）：剩余低于此值则停止转发")
    ap.add_argument("--group-cap", type=int, default=GROUP_CAP,
                    help=f"单次投递摊平上限（默认 {GROUP_CAP}），超出部分留痕后丢弃")
    ap.add_argument("--dry-run", action="store_true", help="只解析与留痕，不打网关")
    ap.add_argument("--force", action="store_true", help="无视既有锁文件强行启动")
    a = ap.parse_args(argv)
    if not _acquire_lock(a.force):
        print("[fatal] 已有一个告警桥实例在运行（小时预算为进程内状态，多实例会成倍转发）——"
              "确认无实例后可加 --force 接管", file=sys.stderr)
        return 3
    bridge = Bridge(a)
    try:
        return serve(bridge, a.port)
    finally:
        _release_lock()


if __name__ == "__main__":
    raise SystemExit(main())
