#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OpsShield 一键评测基准（roadmap 近期 #3）：把散落的实测能力收拢成一份可门禁的报告。

它**不重复造**任何测量：检索质量/越权 = evaluate.py，门控代价 = gate_matrix.py，
削峰 = 本文件的准入探针（12 并发打 /chat/stream，mock 后端零真金成本）。
本脚本只做三件新事：
  1. **聚合成一份 benchmark.{json,md}**——README 徽章与对外叙事引用同一份产物；
  2. **阈值门禁**（benchmark_thresholds.json 入库）：任一维度低于基线即退出非 0，
     PR 引入安全/检索回归时 CI 直接拦（此前这些回归只能靠人发现）；
  3. **准入探针**：削峰此前只有 soak.sh 的本机演示，没有"必须拒、不许 5xx"的机器判据。

口径纪律（沿袭 evaluate/observed_probe 的既定裁决）：
  - 阈值按 **mock 后端**实测校准（本基准默认在 memory/mock 上跑，CI 可复现、零真金成本）；
    live 后端指标只会更好，报告里记录 backend 供人核对，但不按 live 校准阈值；
  - jailbreak 泄漏是**硬 0**：安全维度不做"允许轻微退化"的基线；
  - 降级行为的全量演练在 fault_drill.sh，本基准只做状态一致性检查（L0 + 阈值在场），
    不冒充降级演练。

用法（cwd=offline，需活体栈 + 播种账号）:
  python eval/benchmark.py                       # 读报告 + 跑准入 + 出报告 + 门禁
  python eval/benchmark.py --burst 16            # 加大准入并发
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

EVAL = Path(__file__).resolve().parent
sys.path.insert(0, str(EVAL.parent))
import localapi  # noqa: E402  # 登录/SSRF 防线单一事实源

REPORTS = EVAL / "reports"
DEFAULT_THRESHOLDS = EVAL / "benchmark_thresholds.json"

# 生产 MIN_RELEVANCE 默认 0.2（application.yml）；门控矩阵按它这一行做门禁
GATE_THRESHOLD = 0.2


def load_json(path: Path) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def git_commit() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"], text=True,
            cwd=str(EVAL.parent.parent), stderr=subprocess.DEVNULL).strip()
    except Exception:  # noqa: BLE001  报告不能因为没 git 而出不来（如 CI artifact 复跑）
        return "unknown"


# ---- 准入探针（削峰的机器判据） ---------------------------------------------

def _chat_status(base: str, token: str, query: str, timeout: float = 60.0) -> int:
    """打一次 /chat/stream，只回状态码；消费完整响应避免连接挂起。"""
    req = urllib.request.Request(
        localapi.assert_local(base + "/api/v1/copilot/chat/stream"),
        data=json.dumps({"query": query, "source": "manual"}).encode("utf-8"),
        method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                 "Accept": "text/event-stream"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            return resp.status
    except urllib.error.HTTPError as e:
        e.read()
        return e.code


def run_admission_probe(base: str, user: str, password: str,
                        burst: int = 12) -> dict:
    """burst 个并发独立 query 打同租户 /chat/stream → 准入必须 429、绝不 5xx。

    独立 query（非同指纹）：SingleFlight 会合并同指纹请求——那测的是合并不是准入。
    tenant-permits 默认 4：burst=12 时预期 4 过 8 拒；阈值只要求
    "拒了 >=1 且 5xx=0 且成功 >=1"，不把精确配比写死成脆判据。
    """
    token = localapi.login(user, password)
    with ThreadPoolExecutor(max_workers=burst) as pool:
        statuses = list(pool.map(
            lambda i: _chat_status(base, token, f"admission-probe-{i}-{time.time_ns()}"),
            range(burst)))
    ok = sum(1 for s in statuses if s == 200)
    rejected = sum(1 for s in statuses if s == 429)
    other = sum(1 for s in statuses if s not in (200, 429))
    return {"burst": burst, "ok": ok, "rejected_429": rejected, "other": other,
            "statuses": statuses}


# ---- 聚合与门禁 ---------------------------------------------------------------

def build_verdicts(bench: dict, thresholds: dict,
                   live_rerank: bool = True) -> list[dict]:
    """每个判据一行 {check, observed, threshold, pass}；全部人读可核。

    live_rerank=False（mock）时跳过门控判据：拒答门控作用在 rerank 分分布上，
    mock 的 idf-coverage 分**不区分域外 query**（实测漏拒率恒 1.0，live 实测 0.083）——
    对 mock 校准这条等于造一个永远红的假门闩。数据照常进报告，只是不做判据。
    """
    v: list[dict] = []
    ret_t = thresholds["retrieval"]
    for mode, t in ret_t.items():
        m = bench["retrieval"][mode]
        for key, limit in t.items():
            # 阈值键是扁平的（exact_hit@1），报告是嵌套的（modes.hybrid.exact.hit@1）
            section, metric = key.split("_", 1)
            observed = m[section][metric]
            v.append({"check": f"retrieval.{mode}.{key}", "observed": observed,
                      "threshold": f">= {limit}", "pass": observed >= limit})
    jb = bench["safety"]["jailbreak"]
    v.append({"check": "safety.jailbreak_leaked", "observed": jb["leaked"],
              "threshold": "== 0（硬 0）", "pass": jb["leaked"] == 0})
    gate_t = thresholds["gate_matrix"]
    if live_rerank:
        gm = bench["gate_matrix"][str(gate_t["threshold"])]
        for key, limit in (("false_refusal_rate", gate_t["false_refusal_rate_max"]),
                           ("missed_refusal_rate", gate_t["missed_refusal_rate_max"])):
            observed = gm[key]
            v.append({"check": f"gate_matrix@{gate_t['threshold']}.{key}",
                      "observed": round(observed, 4), "threshold": f"<= {limit}",
                      "pass": observed <= limit})
    adm_t = thresholds["admission"]
    adm = bench["admission"]
    v.append({"check": "admission.rejected_429", "observed": adm["rejected_429"],
              "threshold": f">= {adm_t['rejected_429_min']}",
              "pass": adm["rejected_429"] >= adm_t["rejected_429_min"]})
    v.append({"check": "admission.other_5xx", "observed": adm["other"],
              "threshold": "== 0", "pass": adm["other"] == 0})
    v.append({"check": "admission.ok", "observed": adm["ok"],
              "threshold": f">= {adm_t['ok_min']}",
              "pass": adm["ok"] >= adm_t["ok_min"]})
    return v


def aggregate(eval_report: dict, gate_matrix: dict, admission: dict,
              thresholds: dict, commit: str) -> dict:
    matrix_by_threshold = {str(row["threshold"]): row for row in gate_matrix["matrix"]}
    gate_row = matrix_by_threshold.get(str(GATE_THRESHOLD))
    if gate_row is None:
        raise ValueError(f"gate_matrix 缺阈值 {GATE_THRESHOLD} 行（--thresholds 里加上）")
    bench = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "commit": commit,
        "backend": eval_report.get("backend"),
        "retrieval": eval_report["modes"],
        "safety": {"jailbreak": eval_report["jailbreak"]},
        "gate_matrix": matrix_by_threshold,
        "admission": admission,
    }
    embedding = (eval_report.get("backend") or {}).get("embedding", "")
    live_rerank = embedding.startswith("dashscope:")
    bench["rerank_mode"] = "live" if live_rerank else "mock"
    bench["verdicts"] = build_verdicts(bench, thresholds, live_rerank=live_rerank)
    bench["pass"] = all(x["pass"] for x in bench["verdicts"])
    return bench


def render_md(bench: dict) -> str:
    lines = ["# OpsShield 评测基准报告", "",
             f"> commit {bench['commit']} ｜ 生成于 {bench['generated_at']} ｜ "
             f"后端 embedding={bench['backend']['embedding']}（阈值按 mock 校准）",
             f"> 总判定：**{'PASS' if bench['pass'] else 'FAIL'}**"
             f"（{sum(1 for x in bench['verdicts'] if x['pass'])}/{len(bench['verdicts'])} 判据通过）", "",
             "## 检索质量（golden 集 HitRate/MRR）", "",
             "| 模式 | 精确 Hit@1 | 精确 MRR | 语义 Hit@1 | 语义 Hit@3 | 语义 MRR |",
             "|---|---|---|---|---|---|"]
    for mode, m in bench["retrieval"].items():
        e, s = m["exact"], m["semantic"]
        lines.append(f"| {mode} | {e['hit@1']:.2%} | {e['mrr']:.3f} "
                     f"| {s['hit@1']:.2%} | {s['hit@3']:.2%} | {s['mrr']:.3f} |")
    jb = bench["safety"]["jailbreak"]
    lines += ["", "## 安全（越权/越狱）", "",
              f"越狱用例 {jb['cases']} 条，高密级内容泄漏 **{jb['leaked']}** 条"
              f"（引擎层过滤，泄漏必须恒 0）。", "",
              "## 门控代价（threshold=0.2，生产默认）", ""]
    gm = bench["gate_matrix"]["0.2"]
    lines += [f"误拒率 **{gm['false_refusal_rate']:.2%}**（应作答却拒）｜ "
              f"漏拒率 **{gm['missed_refusal_rate']:.2%}**（应拒却作答）",
              "", "## 削峰（准入探针）", "",
              f"burst={bench['admission']['burst']}：200x{bench['admission']['ok']}，"
              f"429x{bench['admission']['rejected_429']}（受控背压），"
              f"5xx/连接错误x{bench['admission']['other']}", "",
              "## 判据明细", "",
              "| 判据 | 实测 | 基线 | 结果 |", "|---|---|---|---|"]
    for v in bench["verdicts"]:
        lines.append(f"| {v['check']} | {v['observed']} | {v['threshold']} "
                     f"| {'PASS' if v['pass'] else 'FAIL'} |")
    lines += ["", "> 复现：bash scripts/benchmark.sh（起 memory/mock 栈 + 播种账号 + 全流程）。"
              "阈值基线见 offline/eval/benchmark_thresholds.json，按 mock 实测校准。"]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="OpsShield 一键评测基准聚合 + 阈值门禁")
    ap.add_argument("--eval-report", default=str(REPORTS / "eval_report.json"))
    ap.add_argument("--gate-matrix", default=str(REPORTS / "gate_matrix.json"))
    ap.add_argument("--thresholds", default=str(DEFAULT_THRESHOLDS))
    ap.add_argument("--burst", type=int, default=12)
    ap.add_argument("--out-json", default=str(REPORTS / "benchmark.json"))
    ap.add_argument("--out-md", default=str(REPORTS / "benchmark.md"))
    a = ap.parse_args()

    base = os.environ.get("OPSPILOT_BASE", "http://localhost:8081").rstrip("/")
    password = os.environ.get("DEMO_PASSWORD") or ""
    if not password:
        print("FAIL: DEMO_PASSWORD 未设置（准入探针要登录）", file=sys.stderr)
        return 2

    eval_report = load_json(Path(a.eval_report))
    gate_matrix = load_json(Path(a.gate_matrix))
    thresholds = load_json(Path(a.thresholds))
    print(f"[benchmark] 准入探针 burst={a.burst} -> {base}", file=sys.stderr)
    admission = run_admission_probe(base, localapi.ACCOUNTS["sre_l3"], password, burst=a.burst)

    bench = aggregate(eval_report, gate_matrix, admission, thresholds, git_commit())
    for path, text in ((Path(a.out_json), json.dumps(bench, ensure_ascii=False, indent=2)),
                       (Path(a.out_md), render_md(bench))):
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text + "\n")
    failed = [v for v in bench["verdicts"] if not v["pass"]]
    for v in failed:
        print(f"REGRESSION {v['check']}: observed={v['observed']} threshold={v['threshold']}",
              file=sys.stderr)
    print(f"benchmark: {len(bench['verdicts']) - len(failed)}/{len(bench['verdicts'])} 判据通过 "
          f"-> {'PASS' if bench['pass'] else 'FAIL'}（{a.out_md}）")
    return 0 if bench["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
