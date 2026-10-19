# -*- coding: utf-8 -*-
"""左栏预设与覆盖范围的活体复核探针（Step 3，ADR-0021 决策 5 / ADR-0023 决策 5）。

为什么需要它：`index.html` 左栏那四个预设按钮和「覆盖范围」卡片是**对用户的承诺**——
「点一下就能看到完整的排障卡片与命令输出」、「这里是你能问到的知识边界」。承诺挂在三处
彼此独立的事实上，任一处漂移都不会报错、只会让页面变成谎话：

  P-1 预设与页面同源  `PRESETS` 数组里每个 `q` 都必须真在线上跑得出预期形状。预设字符串
                      写在 HTML 里，探针从 HTML 里现读——改了页面不改探针，这里就红。
  P-2 形状（不是产额） 每个预设的 `gate` 是**精确**断言（none / clarify 是协议状态值，
                      不是性能值）；命令条数只断言「有没有」（>=1）与「确实为零」（==0），
                      **不锁具体条数**——产额被 final-top-k 卡住，锁死会让探针随语料扰动
                      假红，这是 action_contract_probe 已经写下的同一条取舍，这里沿用。
  P-3 覆盖范围 = 语料视图 `GET /api/v1/copilot/coverage` 的三个计数与两份清单，必须与
                      `offline/corpus/chunks.jsonl` 按 (tenant, auth_level<=level) 现算的
                      结果**逐项相等**。端点算错了、过滤条件写反了、租户漏了——都在这里红。
                      同时锁死「低密级 ⊆ 高密级」这条不变式（同租户内）。

对本探针自己同样恪守 ADR-0013 的只读红线：
  * 只打三个端点：login、POST /copilot/chat/stream、GET /api/v1/copilot/coverage；
  * **不打任何 /admin/**——没有判据需要清缓存，而清缓存会改变被测系统状态；
  * 语料原文从 offline/corpus/chunks.jsonl 本地只读；
  * HTTP 复用 localapi（环回白名单、不跟随越界重定向都在那儿）；
  * 口令只从环境变量 DEMO_PASSWORD 读，不落字面量。

用法：`python offline/preset_probe.py`（前置：栈已起、.env 已 source）。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import localapi  # noqa: E402

HERE = Path(__file__).resolve().parent
CORPUS = HERE / "corpus" / "chunks.jsonl"
PAGE = HERE.parent / "src" / "main" / "resources" / "static" / "index.html"

CHAT_BUDGET = 6                       # 4 个预设 + 2 余量；超了就是有人忘了删调试形状

# 账号 → (用户名, 租户, auth_level)。来源：scripts/seed_demo_users.sh 的种子注释。
ACCOUNT = {
    "sre_l3": ("sre-full", "tenant-internal", 3),
    "sre_l1": ("sre-limited", "tenant-internal", 1),
    "sre_acme": ("sre-acme", "tenant-acme", 3),
}

# 预设期望：键是**发给网关的那条 query**（标签只是装饰，行为由 query 决定）。
# gate 是精确断言；actions 只给下界或「必须为零」，理由见模块 docstring P-2。
EXPECT = {
    "MySQL 连接池耗尽 HikariCP 超时 排查":
        {"gate": "none", "actions": ("min", 1), "refs": ("min", 1),
         "why": "展示「完整卡片 + 命令输出」的正面样本"},
    "支付网关 502 50041_PAY_GATEWAY_502 排查":
        {"gate": "none", "actions": ("exact", 0), "refs": ("min", 1),
         "why": "命中但引用集里没有可执行围栏——诚实的零命令，页面不得假装有"},
    "订单创建超时 50012_DB_TIMEOUT 定位慢 SQL":
        {"gate": "none", "actions": ("min", 1), "refs": ("min", 1),
         "why": "第二条正面样本（另一服务、另一错误码）"},
    "刚才那个怎么办":
        {"gate": "clarify", "actions": ("exact", 0), "refs": ("exact", 0),
         "why": "触发追问闸门；拒答导航（near_miss）**不得**出现"},
}

results: list[tuple[str, bool, str]] = []
chat_calls = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}")


def info(msg: str) -> None:
    print(f"INFO  {msg}")


# ────────────────────────── 纯函数（单测直接覆盖，不发请求） ──────────────────────────

def parse_presets(html: str) -> list[tuple[str, str]]:
    """从页面源码里现读 `PRESETS` 数组，返回 [(label, q), ...]。

    刻意用**文本**解析而不是执行 JS：这个探针跑在 Python 里，没有 JS 引擎；而「页面改了
    探针没跟上」正是 P-1 要抓的东西——解析失败（数组被删/改名）必须抛，不能返回空表让
    后面每个用例都「没有输入于是跳过」。
    """
    m = re.search(r"const PRESETS\s*=\s*\[(.*?)\n\];", html, re.S)
    if not m:
        raise ValueError("index.html 里找不到 `const PRESETS = [...]`——页面被改名或删除")
    body = m.group(1)
    out = []
    for lm, qm in zip(re.finditer(r"label:\s*'([^']*)'", body),
                      re.finditer(r"q:\s*'([^']*)'", body)):
        out.append((lm.group(1), qm.group(1)))
    if not out:
        raise ValueError("PRESETS 数组解析出 0 个条目——形状变了，探针必须跟着改而不是静默放过")
    return out


def shape_ok(kind: str, want: int, got: int) -> bool:
    return got == want if kind == "exact" else got >= want


def corpus_view(rows: list[dict], tenant: str, level: int) -> dict:
    """本地语料按 (tenant, auth_level<=level) 现算的覆盖范围——端点的对照真值。"""
    sel = [r for r in rows
           if r["metadata"]["tenant"] == tenant and r["metadata"]["auth_level"] <= level]
    codes: set[str] = set()
    for r in sel:
        codes.update(r["metadata"].get("error_codes") or [])
    return {
        "chunks": len(sel),
        "services": sorted({r["metadata"]["service"] for r in sel}),
        "error_codes": sorted(codes),
    }


def coverage_mismatch(payload: dict, truth: dict) -> str:
    """逐项比对端点与本地真值，返回第一条差异（无差异返回空串）。"""
    if payload.get("chunks") != truth["chunks"]:
        return f"chunks 端点={payload.get('chunks')} 语料={truth['chunks']}"
    got_svc = sorted(x["service"] for x in payload.get("service_list") or [])
    if got_svc != truth["services"]:
        return f"services 端点={got_svc} 语料={truth['services']}"
    got_code = sorted(x["code"] for x in payload.get("error_code_list") or [])
    if got_code != truth["error_codes"]:
        return (f"error_codes 端点 {len(got_code)} 条 vs 语料 {len(truth['error_codes'])} 条")
    if payload.get("services") != len(truth["services"]):
        return f"services 计数={payload.get('services')} 清单={len(truth['services'])}"
    if payload.get("error_codes") != len(truth["error_codes"]):
        return f"error_codes 计数={payload.get('error_codes')} 清单={len(truth['error_codes'])}"
    return ""


def load_corpus() -> list[dict]:
    with open(CORPUS, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def fetch_coverage(token: str) -> dict:
    return localapi.get_json("/api/v1/copilot/coverage", token)


class Chatter:
    """chat 调用闸：预算超了直接抛，绝不让探针悄悄打爆网关的 LLM 配额。"""

    def __init__(self, token: str, budget: int):
        self.token = token
        self.budget = budget
        self.used = 0

    def ask(self, query: str) -> dict:
        if self.used >= self.budget:
            raise RuntimeError(f"chat 预算 {self.budget} 已用尽（query={query!r}）")
        self.used += 1
        return localapi.stream_chat({"query": query, "source": "manual"}, self.token)


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    global chat_calls
    try:
        corpus = load_corpus()
    except FileNotFoundError:
        check("corpus-readable", False, str(CORPUS))
        print("\n== 预设探针 0/1 PASS ==")
        return 1
    try:
        presets = parse_presets(PAGE.read_text(encoding="utf-8"))
    except (FileNotFoundError, ValueError) as e:
        check("p1-presets-parsed", False, str(e))
        print("\n== 预设探针 0/1 PASS ==")
        return 1

    # ── P-1 预设与页面同源：每个预设的 query 都得有明确期望 ──
    page_queries = {q for _, q in presets}
    unknown = [q for q in page_queries if q not in EXPECT]
    check("p1-presets-covered", not unknown,
          f"页面 {len(presets)} 个预设，未登记期望的：{unknown}" if unknown
          else f"页面 {len(presets)} 个预设全部有期望")
    stale = [q for q in EXPECT if q not in page_queries]
    check("p1-expectations-live", not stale,
          f"期望表里有页面已删的预设：{stale}" if stale else "期望表无残留")
    if unknown or stale:
        print("\n== 预设探针 0/1 PASS（先对齐 PRESETS 与 EXPECT）==")
        return 1

    tokens = localapi.load_tokens()
    chatter = Chatter(tokens["sre_l3"], CHAT_BUDGET)

    # ── P-2 每个预设跑一次，核形状 ──
    for label, q in presets:
        exp = EXPECT[q]
        res = chatter.ask(q)
        chat_calls += 1
        meta = res.get("meta") or {}
        done = res.get("done") or {}
        actions = done.get("actions") or []
        refs = done.get("refs") or []
        near = done.get("near_miss") or []
        gate = meta.get("gate")
        tag = f"p2-{label}"
        check(f"{tag}-gate", gate == exp["gate"], f"gate={gate} 期望={exp['gate']}")
        ak, av = exp["actions"]
        check(f"{tag}-actions", shape_ok(ak, av, len(actions)),
              f"actions={len(actions)} 期望 {ak} {av}（{exp['why']}）")
        rk, rv = exp["refs"]
        check(f"{tag}-refs", shape_ok(rk, rv, len(refs)), f"refs={len(refs)} 期望 {rk} {rv}")
        if exp["gate"] == "clarify":
            check(f"{tag}-no-near-miss", not near,
                  f"澄清态不得带 near_miss（got {len(near)}）")
        info(f"{tag:<26} gate={gate} refs={len(refs)} actions={len(actions)} "
             f"cache={meta.get('cache_hit')}")

    # ── P-3 覆盖范围 = 语料视图 ──
    seen: dict[tuple[str, int], dict] = {}
    for key in ("sre_l3", "sre_l1", "sre_acme"):
        _, tenant, level = ACCOUNT[key]
        payload = fetch_coverage(tokens[key])
        truth = corpus_view(corpus, tenant, level)
        seen[(tenant, level)] = payload
        diff = coverage_mismatch(payload, truth)
        check(f"p3-coverage-{key}", not diff,
              diff or f"{tenant}/L{level}: {truth['chunks']} 块 · "
                      f"{len(truth['services'])} 服务 · {len(truth['error_codes'])} 错误码")
        check(f"p3-built-at-{key}", payload.get("built_at") is not None,
              f"built_at={payload.get('built_at')}")
        check(f"p3-shape-{key}",
              isinstance(payload.get("facets_truncated"), bool)
              and isinstance(payload.get("service_list"), list)
              and isinstance(payload.get("error_code_list"), list),
              "分面清单/截断标志类型正确")

    # 同租户内：低密级 ⊆ 高密级（auth_level range lte 的直接后果）
    low, high = seen[("tenant-internal", 1)], seen[("tenant-internal", 3)]
    check("p3-lower-level-subset",
          low["chunks"] <= high["chunks"]
          and {x["service"] for x in low["service_list"]}
              <= {x["service"] for x in high["service_list"]}
          and {x["code"] for x in low["error_code_list"]}
              <= {x["code"] for x in high["error_code_list"]},
          f"L1 {low['chunks']} 块 ⊆ L3 {high['chunks']} 块")

    # 跨租户：外部租户的清单不得出现**语料里只属于内部租户**的服务。
    # 「内部独有」取自语料而非取自端点回包——从回包取差集会让这条断言恒真（自证式空转门闩）。
    acme_services = {x["service"] for x in seen[("tenant-acme", 3)]["service_list"]}
    internal_only = ({r["metadata"]["service"] for r in corpus
                      if r["metadata"]["tenant"] == "tenant-internal"}
                     - {r["metadata"]["service"] for r in corpus
                        if r["metadata"]["tenant"] == "tenant-acme"})
    acme_leak = acme_services & internal_only
    check("p3-tenant-isolated-nonvacuous", bool(internal_only),
          f"语料里内部独有服务 {len(internal_only)} 个——为 0 则这条断言无事可做（假绿）")
    check("p3-tenant-isolated", not acme_leak,
          f"tenant-acme 清单里的内部独有服务：{sorted(acme_leak)}" if acme_leak
          else f"内部独有服务 {sorted(internal_only)} 一个都没出现在 tenant-acme 清单里")

    fails = [n for n, ok, _ in results if not ok]
    print(f"\n== 预设探针 {len(results) - len(fails)}/{len(results)} PASS | "
          f"chat 实调 {chat_calls} 次（预算 {CHAT_BUDGET}） ==")
    if fails:
        print("失败项:", ", ".join(fails))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
