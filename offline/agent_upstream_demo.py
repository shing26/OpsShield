"""上游多步排障 agent demo——「agent 是调用方，网关是只读可信底座」（ADR-0013）的最小实证。

定位红线（写给读这份代码的人）：
  - 本文件整体活在网关进程之外：网关**零改动**（不改一行 Java）即被多步编排调用——这正是
    本 demo 的卖点，见 docs/ops/agent-upstream-demo.md 的契约对照表；
  - agent 侧策略是**有界状态机**（至多 MAX_GATEWAY_CALLS 次网关调用，线性可预算），
    不是开放 LLM 循环：网关内不做 tool-calling（ADR-0013 永久非目标），agent 侧也刻意不放大；
  - 网关是无状态单轮语义（ADR-0007）：多步的上下文由 agent 并入每条消息，不指望服务端记忆；
  - 跨步关联靠调用方关联键 X-Trace-Id（ADR-0007 修订注）：一次 incident 的全部步骤在审计里
    可按 trace_id 整段取出；request_id（服务端权威）与 trace_id（调用方提供）不混用；
  - agent 只读：行动契约逐字来自网关（ADR-0028），本 demo 打印建议但**不执行任何命令**；
  - 身份=「JWT 即 Key」（ADR-0007）：走 /api/v1/auth/login 换真实用户 JWT（复用 localapi.login，
    与 console_token.sh 同一条正规路径）。scripts/gen_tokens.py 只签非法凭证（红队样本），
    合法账号一律 login——这是 P2 起的仓库纪律，demo 不为省事破坏它。

与 scripts/mcp_server.py 的分工：MCP 是给 IDE 类 agent 的单发工具面（search/ask 一次一调），
本 demo 演示的是**多步编排**——同一编排面（/v1）被同一个 trace_id 串起来调多次，
呼应 docs/positioning-aiops.md「与 agent 的关系」与 ADR-0013 决策 1。

用法（网关 mock 模式即可，无需 DashScope Key）：
  # 1) 起网关（mock 模式：DASHSCOPE_MODE=mock 或 auto 且无 Key）与中间件
  # 2) 跑 agent（口令从环境读，与 localapi.login 同一口径）
  DEMO_PASSWORD=... offline/.venv/Scripts/python offline/agent_upstream_demo.py
  DEMO_PASSWORD=... python offline/agent_upstream_demo.py --alert-file path/to/alert.json
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import localapi  # noqa: E402  （登录/出口都走 localapi，见 GatewayClient 的单基址说明）

# 预算上限：一次 incident 至多 2 次网关调用（初问 + 至多一次追问）。线性、可预算，
# 超 budget 即升级给人——agent 不允许"再多试一轮"的滑坡（ADR-0013：线性链路的可预算是可信前提）。
MAX_GATEWAY_CALLS = 2

# 与 JwtAuthFilter/RequestIdFilter 的约定对齐：8–64 位 [A-Za-z0-9_-]，非法即 400。
TRACE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")

# 网关置信度拒答的固定话术标记（ChatOrchestrator 空态门控分支），agent 据此分支。
REFUSAL_MARKER = "无法可靠作答"

# OpenAI 面把溯源与只读契约以 markdown 注入正文尾部（ADR-0007 决策 4 / OpenAiChatSink）。
REFS_HEADER = "## 参考来源"
ACTIONS_HEADER = "## 只读行动契约"

# 网关内部异常时的收尾话术（OpenAiChatSink.deliverFailure）——带此标记的正文不是结论，
# agent 不得把它当排障结论渲染给人。
FAILURE_MARKER = "【系统提示】本轮回答因网关内部异常中断"

# 告警文本里的错误码形状（与 console_client 风暴样例同源：50012_DB_TIMEOUT 这类下划线大码）。
# 前缀允许数字开头，后缀必须是全大写且至少 3 位，避免把正文里的普通单词误当标识符。
ERROR_CODE_RE = re.compile(r"\b[0-9A-Z]{2,}_[A-Z][A-Z0-9_]{2,}\b")


@dataclass
class ParsedAnswer:
    """一次 /v1 调用的解析结果：正文、溯源引用、只读行动契约、拒答/异常标记。"""

    text: str
    refs: list[tuple[str, str]] = field(default_factory=list)  # (breadcrumb, service)
    actions: list[tuple[str, str]] = field(default_factory=list)  # (step, command)
    refusal: bool = False
    failed: bool = False  # 网关异常中断帧：正文被系统提示收尾，不是结论


@dataclass
class Step:
    """一步的记录：agent 决策留痕，也方便 demo 时逐段讲。"""

    n: int
    phase: str  # triage / followup-refusal / followup-actions / escalate
    query: str
    outcome: str = ""  # answered / refusal / no-actions / escalated
    calls: int = 0


class GatewayClient:
    """/v1 OpenAI 兼容面的最小客户端：JWT 即 Key，SSE 流拼接，401 自愈重登一次。

    出口一律经 localapi.assert_local（http+环回+解析后 IP 仍环回），与本仓其余验收脚本
    同一条 SSRF 防线，不另起炉灶。
    """

    def __init__(self, base: str, username: str, password: str):
        # localapi 是**单基址**设计：所有原语（login/post_json/_open）都读模块级 BASE。
        # 故 --base 必须落到 localapi.BASE 上，否则登录会打到默认 8081 而与 chat 的基址错位。
        # 这是本 demo 对 localapi 的唯一写操作，且写的是它自己的公开覆盖口（同 _resolve_base 的意图）。
        self.base = localapi.assert_local(base.rstrip("/"))
        localapi.BASE = self.base
        self.username = username
        self.password = password
        self._token: str | None = None

    def _ensure_token(self) -> str:
        if not self._token:
            # 合法账号一律走 /api/v1/auth/login（JWT 即 Key 的正规路径），不读 redteam_tokens.txt。
            self._token = localapi.login(self.username, self.password)
        return self._token

    def chat(self, message: str, trace_id: str) -> str:
        """一次 chat.completions（仅 stream=true，网关 /v1 只提供流式）。

        无状态单轮语义：service/env 由网关按本面固定口径取 manual/prod（OpenAiController），
        多步上下文由调用方并入 message——本方法不做任何服务端会话假设。
        401 时清 token 重登再试一次（token 24h 过期是常态路径），再 401 即失败。
        """
        for attempt in (1, 2):
            body = json.dumps({
                "model": "opspilot",
                "stream": True,
                "messages": [{"role": "user", "content": message}],
            }).encode("utf-8")
            req = urllib.request.Request(
                localapi.assert_local(self.base + "/v1/chat/completions"),
                data=body, method="POST",
                headers={"Authorization": "Bearer " + self._ensure_token(),
                         "Content-Type": "application/json",
                         "Accept": "text/event-stream",
                         "X-Trace-Id": trace_id})
            try:
                # 出口走 localapi._open：与其余验收脚本同一条防线（白名单 + 不跟随越界重定向）。
                with localapi._open(req, 120) as resp:
                    return _join_sse(resp)
            except urllib.error.HTTPError as e:
                if e.code == 401 and attempt == 1:
                    self._token = None  # 过期/被吊销：重登一次再试
                    continue
                detail = e.read().decode("utf-8", "ignore")[:200]
                raise RuntimeError(f"/v1 调用失败 HTTP {e.code}: {detail}") from e
        raise RuntimeError("/v1 调用失败：重登后仍 401（检查账号状态与 DEMO_PASSWORD）")


def _join_sse(resp) -> str:
    """拼 OpenAI SSE 流：逐 delta.content 帧，[DONE] 收尾。坏帧跳过不致命。"""
    parts: list[str] = []
    for raw in resp:
        line = raw.decode("utf-8").rstrip("\n")
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload == "[DONE]":
            break
        try:
            chunk = json.loads(payload)
        except json.JSONDecodeError:
            continue
        choices = chunk.get("choices") or []
        if choices:
            delta = choices[0].get("delta") or {}
            content = delta.get("content")
            if content:
                parts.append(content)
    return "".join(parts)


def parse_answer(text: str) -> ParsedAnswer:
    """把 OpenAI 面的正文拆成 结论 / 只读行动契约 / 参考来源，并识别拒答与异常中断。

    注入顺序以 OpenAiChatSink 为准：成功帧是 答案 → 行动契约 → 参考来源；异常帧是
    行动契约 → 参考来源 → 【系统提示】（deliverFailure）。故**不能**按"先切契约再在
    body 里找来源"的顺序切——那样来源段会随契约段一起被切走。这里改成先按两个标题的
    位置切出各自区段（顺序无关），答案 = 首个标题之前的正文。

    标题一律取**最后一次出现**（rfind）：标题由网关追加在正文尾部，LLM 正文里若恰好
    提到同名 markdown 标题，取尾部那次才不会被正文截断。
    """
    parsed = ParsedAnswer(text=text, refusal=REFUSAL_MARKER in text,
                          failed=FAILURE_MARKER in text)
    # 异常帧的系统提示是收尾话术，不属于任何结论：先摘掉再解析区段（契约/来源仍在它之前）。
    cleaned = text.split(FAILURE_MARKER)[0].rstrip() if parsed.failed else text

    found = sorted((cleaned.rfind(h), h) for h in (ACTIONS_HEADER, REFS_HEADER)
                   if h in cleaned)
    sections: dict[str, str] = {}
    for k, (start, header) in enumerate(found):
        end = found[k + 1][0] if k + 1 < len(found) else len(cleaned)
        sections[header] = cleaned[start + len(header):end]
    body = cleaned[:found[0][0]] if found else cleaned

    for m in re.finditer(r"^\d+\.\s*(.+?)\n\s*`(.+?)`\s*$",
                         sections.get(ACTIONS_HEADER, ""), re.M):
        parsed.actions.append((m.group(1).strip(), m.group(2).strip()))
    for m in re.finditer(r"^-\s*(.+?)（(.+?)）\s*$", sections.get(REFS_HEADER, ""), re.M):
        parsed.refs.append((m.group(1).strip(), m.group(2).strip()))
    parsed.text = body.strip()
    return parsed


def build_query(alert: dict) -> str:
    """告警 → 单条 query（网关按最后一条 user 消息取 query，多步上下文由客户端并入）。"""
    labels = alert.get("labels") or {}
    text = alert.get("text") or (alert.get("annotations") or {}).get("summary") or ""
    lines = ["收到一条运维告警，请给出排障结论与只读排查步骤：", f"告警内容：{text}"]
    if labels.get("service"):
        lines.append(f"服务：{labels['service']}")
    if labels.get("env"):
        lines.append(f"环境：{labels['env']}")
    return "\n".join(lines)


def alert_identifiers(alert: dict) -> list[str]:
    """从告警里取可追问的标识符（错误码、服务名）——拒答后补上下文的素材。

    网关拒答话术只说"请补充错误码或服务名"，OpenAI 面不回 near-miss 候选（那在 meta 面）；
    agent 的优势恰是手里有原始告警，能补上网关单轮 query 里没有的标识符。
    """
    text = alert.get("text") or (alert.get("annotations") or {}).get("summary") or ""
    ids = ERROR_CODE_RE.findall(text)
    service = (alert.get("labels") or {}).get("service")
    if service:
        ids.append(service)
    return ids


def new_trace_id() -> str:
    """调用方关联键：agent- 前缀 + 12 位 hex，落在 [A-Za-z0-9_-]{8,64} 内。"""
    return "agent-" + uuid.uuid4().hex[:12]


class TriageAgent:
    """两步排障 agent：初问 → 按网关结论分支（补标识符追问 / 要只读契约 / 直接汇报）。

    决策表（全部确定性，无 LLM 参与编排）：
      步骤1 answered 且带行动契约 → 直接进汇报（预算只花 1 次调用）；
      步骤1 answered 但无行动契约 → 步骤2 追问"给出只读排查步骤"；
      步骤1 拒答                  → 步骤2 用告警自身标识符补上下文重问；
      任一步收到异常中断帧         → 立即升级（该帧是系统提示收尾，不是结论）；
      步骤2 仍拒答                → 升级给人（带 trace_id 与已试过的问法），绝不第三次调用。
    """

    def __init__(self, client: GatewayClient, trace_id: str | None = None):
        self.client = client
        self.trace_id = trace_id or new_trace_id()
        self.steps: list[Step] = []

    def run(self, alert: dict) -> dict:
        query = build_query(alert)
        report = {"trace_id": self.trace_id, "steps": self.steps,
                  "gateway_calls": 0, "answer": None, "escalated": False}

        step1 = Step(1, "triage", query, calls=1)
        self.steps.append(step1)
        first = parse_answer(self.client.chat(query, self.trace_id))
        report["gateway_calls"] += 1
        report["budget"] = MAX_GATEWAY_CALLS

        # 异常中断帧不是结论（OpenAiChatSink.deliverFailure）：正文被系统提示收尾，
        # 再追问也只会把同一故障花掉第二次预算，故直接升级，不做"重试一轮"的滑坡。
        if first.failed:
            step1.outcome = "gateway-failed"
            return self._escalate(report, "网关本轮回答异常中断（deliverFailure 帧），未取得可用结论")

        if not first.refusal and first.actions:
            step1.outcome = "answered"
            report["answer"] = first
            return report

        # —— 第 2 步：基于第 1 步的结论决定追问方向 ——
        if first.refusal:
            step1.outcome = "refusal"
            ids = alert_identifiers(alert)
            if not ids:
                return self._escalate(report, "网关拒答且告警中无可补充的标识符（错误码/服务名）")
            followup = (f"接上一条：你反馈「无足够相关的参考」。补充标识符后请重新给出排障结论与"
                        f"只读排查步骤。原始告警：{query}\n补充标识符：{'、'.join(ids)}")
            phase = "followup-refusal"
        else:
            step1.outcome = "no-actions"
            followup = (f"接上一条：结论已收到，但缺少可执行的排查步骤。"
                        f"请针对上述问题给出只读行动契约（只读命令，不要任何写操作）。原始问题：{query}")
            phase = "followup-actions"

        step2 = Step(2, phase, followup, calls=1)
        self.steps.append(step2)
        second = parse_answer(self.client.chat(followup, self.trace_id))
        report["gateway_calls"] += 1

        if second.failed:
            step2.outcome = "gateway-failed"
            return self._escalate(report, "追问本轮回答异常中断（deliverFailure 帧），未取得可用结论")
        if second.refusal:
            step2.outcome = "refusal"
            return self._escalate(report, "补充标识符后网关仍拒答（知识库可能未覆盖该故障）")
        step2.outcome = "answered"
        report["answer"] = second
        return report

    def _escalate(self, report: dict, reason: str) -> dict:
        report["escalated"] = True
        report["escalation_reason"] = reason
        return report


def render(report: dict) -> str:
    """处置建议输出：结论 + 逐条溯源 + 逐字行动契约 + trace_id，供人执行。"""
    lines = ["== OpsShield 上游多步排障 agent demo ==",
             f"trace_id: {report['trace_id']}  （审计按此键可整段取出本次 incident 全部步骤）",
             f"网关调用次数: {report['gateway_calls']} / 预算 {report.get('budget', MAX_GATEWAY_CALLS)}",
             ""]
    for s in report["steps"]:
        lines.append(f"[步骤{s.n} · {s.phase} · {s.outcome}] {s.query.splitlines()[0]}")
    lines.append("")
    if report.get("escalated"):
        lines.append(f"【升级给人】{report.get('escalation_reason', '')}")
        lines.append("已问过的问法与 trace_id 如上，请值班 SRE 人工接手。")
        return "\n".join(lines)

    ans: ParsedAnswer = report["answer"]
    lines.append("【处置建议 · 供人执行；agent 不执行任何命令（底座只读承诺，谱系⑤）】")
    lines.append("")
    lines.append("结论：")
    lines.append(ans.text)
    if ans.actions:
        lines.append("")
        lines.append("只读行动契约（逐字来自网关，未经 agent 改写）：")
        for i, (step, cmd) in enumerate(ans.actions, 1):
            lines.append(f"  {i}. {step}")
            lines.append(f"     {cmd}")
    if ans.refs:
        lines.append("")
        lines.append("溯源：")
        for breadcrumb, service in ans.refs:
            lines.append(f"  - {breadcrumb}（{service}）")
    return "\n".join(lines)


SAMPLE_ALERT = {
    "labels": {"alertname": "OrderErrorSpike", "service": "order-service",
               "env": "prod", "severity": "critical"},
    "annotations": {"summary": "下单接口持续报错，5 分钟内错误率超过阈值"},
    "text": ("org.springframework.jdbc.SQLTransientException: "
             "error code 50012_DB_TIMEOUT at "
             "com.ordercenter.order.OrderCreateService.createOrder"),
}


def main() -> int:
    ap = argparse.ArgumentParser(description="OpsShield 上游多步排障 agent demo")
    ap.add_argument("--alert-file", help="告警 JSON 文件（缺省用内置样例告警）")
    ap.add_argument("--base", default=os.environ.get("OPSPILOT_BASE", "http://localhost:8081"))
    ap.add_argument("--user", default="sre-full", help="登录用户名（缺省 demo 全权账号）")
    args = ap.parse_args()

    if args.alert_file:
        alert = json.loads(Path(args.alert_file).read_text(encoding="utf-8"))
    else:
        alert = SAMPLE_ALERT

    password = os.environ.get("DEMO_PASSWORD")
    if not password:
        print("ERROR: DEMO_PASSWORD 环境变量未设置（口令只从环境读取）", file=sys.stderr)
        return 1

    client = GatewayClient(args.base, args.user, password)
    agent = TriageAgent(client)
    t0 = time.time()
    try:
        report = agent.run(alert)
    except (RuntimeError, ValueError) as e:
        print(f"FAIL: {e}", file=sys.stderr)
        return 1
    print(render(report))
    print(f"\n[agent] 完成：{report['gateway_calls']} 次网关调用，耗时 {time.time()-t0:.1f}s，"
          f"trace_id={report['trace_id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
