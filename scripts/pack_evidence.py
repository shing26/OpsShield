#!/usr/bin/env python3
"""证据链一键打包——把"愿意翻就能翻"变成"一条命令给我快照"。

为什么需要它：本项目的全部差异化是"每个出口都可核验"，随附产物也确实都在库
（`offline/eval/reports/`、`offline/load/reports/`、`docs/qa/`、11 份 ADR）。但证据
**散在五个落点**，且资源约束是双模的（无 key 走 mock、有 key 走 live，两条路径产物形态不同）。
结果是核验成本被转嫁给了读者：README 只能写"数字底稿与指标出处即 …"，剩下的靠对方自己翻。

这个脚本把核验成本拿回来：一条命令产出一个自述快照，档内 MANIFEST.md 逐条写明
「这个数字来自哪个文件、用什么命令产生、在**当前模式**下能不能复核」。

三个刻意的设计决定：

1. **mode 是必需字段，不是装饰**。本项目双模：同一个数字在不同模式下不可比
   （README 明写 mock 环境不承诺指标表数字）。归档若不带 mode，读者无法判断
   手上这份快照的数字属于哪个语义世界。

2. **`logs/` 下的运行态证据是"局部证据"，必须显式标注**。`logs/` 已 gitignore
   （`git ls-files logs/` 为空），干净检出里**不存在**。归档只在它存在时纳入，
   并标为"本机独有、第三方无法复核"——不标就等于假装它也随仓发布。

3. **不写入任何凭据**。模式判定只看 `DASHSCOPE_API_KEY` 是否存在（`set/absent`），
   既不打印也不落盘它的值。归档要公开给人看，这条是硬约束。

4. **出包前先自检"随附报告与语料同代"**（复用 `offline/provenance.py` 的判据）。
   本档对外的全部承诺是"这份快照自洽、每个数字都能在这里复核"；悄悄打出一份
   **报告已过期**的快照，是最不该发生的一次出包——读者会拿旧数字当现行结论，
   而 E1 那类事故正是这么传播的。故默认**拒绝**，放行要显式 `--allow-stale`，且档内显著留痕。

用法（任意 cwd）:
  python scripts/pack_evidence.py                 # → _archive/evidence/<时间戳>-<sha>-<mode>/
  python scripts/pack_evidence.py --zip           # 另产同名 .zip
  python scripts/pack_evidence.py --out /tmp/ev   # 指定输出父目录
  python scripts/pack_evidence.py --allow-stale   # 报告与语料分叉时仍出包（档内会标注）
零凭据、零网络、仅 stdlib：干净检出下可直接跑（mock 模式）。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# 同代判据在离线侧（`offline/provenance.py`），这里复用同一份实现而不是另写一套：
# 两套判据会在某次修订后悄悄分叉，而"打包自检"与"CI 门闩"给出不同结论时，
# 读者无从判断哪个才算数。经 sys.path 正常导入（非 importlib 按路径加载），
# 这样对它的变异才能传导到本脚本的回归锁里（同 offline/tests 的既有教训）。
OFFLINE = REPO / "offline"
if str(OFFLINE) not in sys.path:
    sys.path.insert(0, str(OFFLINE))
import provenance as pv  # noqa: E402

# 复核前提的四个等级——MANIFEST 逐条标注，读者据此判断"我现在能不能验这一项"。
CLEAN = "clean"   # 干净检出即可复核（纯静态产物，无外部依赖）
STACK = "stack"   # 需活体栈（docker compose up -d + 网关在跑）
KEY = "key"       # 需 DASHSCOPE_API_KEY（live 口径数字；mock 下不成立）
LOCAL = "local"   # 本机独有，不入库，第三方无法复核

PREREQ_LABEL = {
    CLEAN: "干净检出即可复核",
    STACK: "需活体栈（compose + 网关）",
    KEY: "需 DASHSCOPE_API_KEY（live 口径）",
    LOCAL: "本机独有（不入库，第三方无法复核）",
}

# 证据登记表：路径 → (产生它的命令, 复核前提)
REGISTRY: list[tuple[str, str, str]] = [
    ("offline/eval/reports/eval_report.md", "cd offline && python eval/build_golden.py && python eval/evaluate.py", KEY),
    ("offline/eval/reports/eval_report.json", "cd offline && python eval/evaluate.py", KEY),
    ("offline/eval/reports/PROVENANCE.json", "cd offline && python provenance.py --stamp（evaluate.py 自动刷新）", CLEAN),
    ("offline/eval/reports/gate_matrix.md", "cd offline && python eval/gate_matrix.py（门控混淆矩阵：误拒/漏拒两类，需活体栈真检索）", KEY),
    ("offline/eval/reports/gate_matrix.json", "cd offline && python eval/gate_matrix.py", KEY),
    ("offline/eval/reports/observed_probe.md", "cd offline && python eval/observed_probe.py（真实输入探测：零召回率与快路径命中）", KEY),
    ("offline/eval/reports/observed_probe.json", "cd offline && python eval/observed_probe.py", KEY),
    ("offline/eval/reports/mode_ablation.md", "cd offline && python eval/mode_ablation.py（三模式消融 + id 级对照：融合到底贡献了什么）", KEY),
    ("offline/eval/reports/mode_ablation.json", "cd offline && python eval/mode_ablation.py", KEY),
    ("offline/eval/reports/grounding_report.json", "cd offline && python qa_gen_quality_probes.py V2 V4 V9（接地报告；判据见 offline/grounding.py）", KEY),
    ("offline/load/reports/l1_trigger.md", "cd offline && python load/sweep.py --levels 48 --duration 30s --scenario unique --report l1_trigger（降级复核：唯一指纹压到 L1 触发线）", KEY),
    ("offline/load/reports/l1_trigger.json", "同上", KEY),
    ("offline/eval/golden_dataset.jsonl", "cd offline && python eval/build_golden.py", CLEAN),
    ("offline/corpus/chunks.jsonl", "cd offline && python chunkers/build_chunks.py", CLEAN),
    ("offline/load/reports/l1_hit_latency.md", "cd offline && python load/l1_latency.py", STACK),
    ("offline/load/reports/l1_hit_latency.json", "cd offline && python load/l1_latency.py", STACK),
    ("offline/load/reports/locust_a.html", "cd offline && locust -f load/locustfile.py --headless -u 50 -t 30s --html load/reports/locust_a.html", STACK),
    ("offline/load/reports/locust_b.html", "cd offline && SCENARIO=storm locust -f load/locustfile.py --headless -u 500 -t 20s --html load/reports/locust_b.html", STACK),
    ("offline/load/reports/", "同上传入脚本同步产出的 stats/failures/exceptions CSV", STACK),
    ("docs/qa/", "人工/Agent 验收记录（三轮 Persona / 模块验证 / 自举闭环）", CLEAN),
    ("docs/adr/", "架构决策记录（改架构先写 ADR）", CLEAN),
    ("docs/ops/production-readiness-2026-09-12.md", "生产就绪度台账与触发线", CLEAN),
    ("README.md", "门面：正文引用的每个数字都应能在本档内找到出处", CLEAN),
    ("docs/repo-map.md", "仓库地图与文档分工（维护者向：目录职责 / scripts 一览 / 收纳规矩）", CLEAN),
    ("OPS.md", "运维速查：债务闹钟表与 SOP", CLEAN),
    ("docs/DEMO.md", "演示主线（幕①–⑧）与讲解稿", CLEAN),
    # 运行态证据：gitignore，干净检出里不存在——只在存在时纳入并标 local
    ("logs/audit.jsonl", "运行期由网关自动落盘（合规审计，14 天滚动）", LOCAL),
    ("logs/alert-producer.jsonl", "bash scripts/alert_producer.sh 或 python offline/alert_producer.py", LOCAL),
    ("logs/.alert-producer.lock", "告警生产者单实例锁（mtime 判活）", LOCAL),
]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def git_facts() -> dict:
    """sha + 工作区是否脏。脏的时候 sha 不能完整描述这份归档，必须如实登记。"""
    def run(*args: str) -> str:
        try:
            return subprocess.run(args, cwd=REPO, capture_output=True, text=True,
                                  timeout=30, check=False).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return ""
    sha = run("git", "rev-parse", "HEAD")
    dirty = run("git", "status", "--porcelain")
    return {
        "sha": sha or "unknown",
        "dirty": bool(dirty),
        "dirty_paths": [ln.split(maxsplit=1)[-1] for ln in dirty.splitlines()[:50]] if dirty else [],
    }


def detect_mode() -> str:
    """只看 key 存在与否（set/absent）；不读值、不打印值、不落盘值。"""
    return "live" if (os.environ.get("DASHSCOPE_API_KEY") or "").strip() else "mock"


def load_provenance() -> dict | None:
    p = REPO / "offline" / "eval" / "reports" / "PROVENANCE.json"
    if not p.is_file():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _digests(facts: dict) -> dict:
    """受门闩约束的三项内容摘要 + 计数，供 MANIFEST.json 留痕（人复核时不用再跑一遍）。"""
    out: dict[str, dict] = {}
    for key in pv.GATED_INPUTS:
        cur = facts.get(key) or {}
        out[key] = {"sha256": cur.get("sha256"),
                    "counts": {k: v for k, v in cur.items() if k != "sha256"}}
    return out


def same_generation() -> dict:
    """出包前的同代自检：随附报告与当前语料是否同代（判据实现见 offline/provenance.py）。

    返回结构化结论而不是布尔值：拒绝出包时要能说清**哪一项**分叉了，
    放行时也要把这份差异原样写进档内（"显式放行"必须留下可审计的痕迹）。
    """
    recorded = pv.load_provenance()
    current = pv.collect()
    if recorded is None:
        return {"ok": False, "reason": "missing", "diffs": [],
                "detail": f"缺 {pv.PROVENANCE_REL}——无法判定随附报告与语料是否同代",
                "digests": _digests(current)}
    diffs = pv.diff(recorded, current)
    return {
        "ok": not diffs,
        "reason": "ok" if not diffs else "diverged",
        "detail": "随附报告与语料同代" if not diffs else "随附报告与语料已分叉",
        "recorded_at": recorded.get("generated_at"),
        "reproduce": recorded.get("reproduce"),
        "diffs": diffs,
        "digests": _digests(current),
    }


def same_generation_md(gen: dict) -> str:
    """§0 里的同代自检段——读者最先要判断的正是"这份快照里的数字是不是现行结论"。"""
    if gen.get("ok"):
        return ("**同代自检：通过** —— 随附报告与语料同代（三项内容摘要见 `MANIFEST.json` 的 "
                "`same_generation.digests`）。判据与 CI 的 `provenance` job 同一份实现，"
                "可独立复核：\n\n```bash\ncd offline && python provenance.py --check\n```")
    diffs = "\n".join(f"  - {d}" for d in gen.get("diffs", [])) or "  - （无差异明细）"
    return ("\n".join([
        "> ⚠️ **同代自检：未通过——本档是显式放行的过期快照（`--allow-stale`）**",
        f"> {gen.get('detail', '')}。**报告里的指标数字不是现行结论**，只可用于取证/对比，",
        "> 不可作为「当前能力」引用。差异：",
        diffs,
        "> 修法：`cd offline && python eval/build_golden.py && python eval/evaluate.py`，"
        "再 `python provenance.py --stamp`，然后不带 `--allow-stale` 重新打包。",
    ]))


def _hidden(path: Path, root: Path) -> bool:
    """目录项展开时判定"隐藏段"（任一路径段以 `.` 开头）。

    为什么需要这道闸：目录登记项会扫进"跑打包时这台机器上恰好存在"的任何文件，而它们**不在版本库里**。
    实测踩到过——本工作区的 Mimosa 安全钩子会在 `docs/adr/` 下写 `.mimosa/hook-status/sess_*.json`：
    它被 `.gitignore` 忽略（所以 `git status` 干净、人工复核也看不见），却让证据包多出一条，
    于是**同一提交**本机 47 产物 vs CI 干净检出 46——而"能在干净检出下复核"正是本档存在的理由。

    显式登记的单文件不受本规则约束（`logs/.alert-producer.lock` 是刻意登记的）。
    """
    return any(part.startswith(".") for part in path.relative_to(root).parts)


def collect(include_local: bool = False) -> tuple[list[dict], list[str]]:
    """按登记表收集存在的产物。返回 (条目列表, 未纳入说明)。

    按路径去重：登记表里既有单文件也有整目录（如 `locust_a.html` 与 `offline/load/reports/`），
    不去重会让同一文件在 CHECKSUMS 与 MANIFEST.json 里出现两次。

    `include_local=False`（默认）不纳入 LOCAL 类证据：本档的用途是"给第三方核验的快照"，
    而运行态日志第三方**不可能**核验，纳入只会增加分发时的泄漏面（审计行含查询内容与租户名）。
    默认"可核验优先"，要看运行态证据时显式 `--include-local-evidence`。
    """
    by_path: dict[str, dict] = {}
    skipped: list[str] = []
    for rel, produce, prereq in REGISTRY:
        src = REPO / rel
        if not src.exists():
            skipped.append(f"{rel}（{PREREQ_LABEL[prereq]}）——未生成，故不在库内")
            continue
        if prereq == LOCAL and not include_local:
            skipped.append(f"{rel}（{PREREQ_LABEL[prereq]}）——"
                           f"默认不纳入；需 `--include-local-evidence` 显式开启")
            continue
        if src.is_file():
            files = [src]
        else:
            all_members = sorted(p for p in src.rglob("*") if p.is_file())
            files = [p for p in all_members if not _hidden(p, src)]
            # 跳过的隐藏项显式登记：静默少一条与"没生成"长得一样，而本档的纪律正是禁静默空洞
            skipped.extend(
                f"{p.relative_to(REPO).as_posix()}（隐藏项）——目录项展开跳过："
                f"`.` 开头的机器本地文件不在版本库里，纳入会让本机包与干净检出包不一致"
                for p in all_members if _hidden(p, src))
        for f in files:
            key = f.relative_to(REPO).as_posix()
            if key in by_path:
                continue
            by_path[key] = {
                "path": key,
                "bytes": f.stat().st_size,
                "sha256": sha256_file(f),
                "produce": produce,
                "prereq": prereq,
            }
    return sorted(by_path.values(), key=lambda x: (x["prereq"], x["path"])), skipped


def mode_verdict(mode: str, prov: dict | None) -> str:
    """本归档能不能复核随附报告里的数字——这是整份 MANIFEST 里最该先说清的一句。"""
    if prov is None:
        return ("随附评测报告的出处登记（`PROVENANCE.json`）缺失，无法判断报告与语料是否同代。"
                "先 `cd offline && python provenance.py --check`。")
    rep_mode = prov.get("report_mode", "unknown")
    if mode == rep_mode == "live":
        return ("本归档在 **live** 环境下生成，随附报告同为 live 口径——档案内的数字彼此可比。"
                "注意 live 数字含模型版本与账户配额的影响，跨日复跑会有正常波动。")
    if mode == "mock" and rep_mode == "live":
        return ("**本归档在 mock 环境生成，而随附报告是 live 口径——报告里的数字在这里复核不出来。** "
                "mock 能复核的是**机制**（链路、鉴权、降级、缓存、口径），不是那些指标值。"
                "要复核 live 数字：在 `.env` 填 `DASHSCOPE_API_KEY` 后重跑评测与压测，再重新打包。")
    if mode == "live" and rep_mode == "mock":
        return ("当前是 live 环境，但随附报告登记为 mock 口径——报告数字保守（词法后端），"
                "不代表 live 能力。重跑评测即可刷新为 live 口径。")
    return "本归档与随附报告同为 mock 口径：可复核机制与口径，指标值不承诺。"


def render_manifest(meta: dict, items: list[dict], skipped: list[str], prov: dict | None) -> str:
    by_prereq: dict[str, list[dict]] = {}
    for it in items:
        by_prereq.setdefault(it["prereq"], []).append(it)

    L: list[str] = []
    L.append("# OpsPilot 证据链快照 — MANIFEST")
    L.append("")
    L.append(f"> 生成时间：{meta['generated_at']} ｜ 打包脚本：`scripts/pack_evidence.py`")
    L.append(f"> `git_sha`：`{meta['git']['sha']}`"
             + ("（**工作区有未提交改动**，此 sha 不完整描述本档来源）" if meta["git"]["dirty"] else "（工作区干净）"))
    L.append(f"> **mode：`{meta['mode']}`**（判定依据：`DASHSCOPE_API_KEY` "
             f"{'set' if meta['mode'] == 'live' else 'absent'}；凭据值不读取、不收录）")
    L.append(f"> Python：{meta['python']} ｜ 产物 {len(items)} 个文件")
    L.append("")
    L.append("## 0. 本档能复核什么（先读这一段）")
    L.append("")
    L.append(mode_verdict(meta["mode"], prov))
    L.append("")
    gen = meta.get("same_generation")
    L.append(same_generation_md(gen) if gen else
             "**同代自检：未记录** —— 本档未携带同代自检结论（正常出包必带，见 `--allow-stale` 与 "
             "`scripts/pack_evidence.py` 的设计决定 4）。")
    L.append("")
    if by_prereq.get(LOCAL):
        L.append("> ⚠️ **本档含本机运行态证据**（`logs/` 下审计与告警运行史，见 §1 末组）："
                 "它们不在库内、第三方无法复核，且审计行含查询内容与租户标识。"
                 "**不要原样公开分发**；对外分享请去掉 `logs/` 或改用默认（不带 `--include-local-evidence`）重打。")
        L.append("")
    if meta["git"]["dirty"]:
        L.append("**工作区改动清单（本档包含未提交内容）**：")
        L.append("")
        for p in meta["git"]["dirty_paths"]:
            L.append(f"- `{p}`")
        L.append("")
    if prov:
        L.append("### 随附报告与语料的同代关系（来自 `PROVENANCE.json`）")
        L.append("")
        L.append(f"- 报告口径：`{prov.get('report_mode', '?')}` ｜ 后端："
                 f"`{(prov.get('report_backend') or {}).get('embedding', '?')}`")
        L.append(f"- 语料文档：{prov.get('corpus_docs', {}).get('files', '?')} 篇 "
                 f"｜ 内容摘要 `{str(prov.get('corpus_docs', {}).get('sha256'))[:16]}…`")
        L.append(f"- 切分产物：{prov.get('chunks', {}).get('lines', '?')} chunks "
                 f"｜ 内容摘要 `{str(prov.get('chunks', {}).get('sha256'))[:16]}…`")
        L.append(f"- 评测集：{prov.get('golden', {}).get('samples', '?')} 样本 "
                 f"｜ 内容摘要 `{str(prov.get('golden', {}).get('sha256'))[:16]}…`")
        L.append("")
        L.append("复核这条关系只需一条命令（零凭据、零网络）：")
        L.append("")
        L.append("```bash")
        L.append("cd offline && python provenance.py --check")
        L.append("```")
        L.append("")
        L.append("它比对的是**内容摘要**而非 mtime——CI 的 `git checkout` 会把所有 mtime 刷成检出时刻，"
                 "时间戳判据在那里恒真（等于没有门闩）。")
        L.append("")
        L.append("> **两个 sha256 的分工**（同名词不同用途，别当成对不上）：上面这些是**内容摘要**，"
                 "把行尾归一为 LF 后计算——判「是不是同一代内容」，与检出约定无关；"
                 "而 §1 表内与 `CHECKSUMS.sha256` 是**本档字节的 sha256**（未归一），"
                 "用于 `sha256sum -c` 校验快照自身完整。同一文件在本机（CRLF 工作区）两者会不同，"
                 "这是预期行为，不是损坏。")
        L.append("")

    L.append("## 1. 逐项出处（每个数字来自哪个文件、由什么产生）")
    L.append("")
    for prereq in (CLEAN, STACK, KEY, LOCAL):
        group = by_prereq.get(prereq)
        if not group:
            continue
        L.append(f"### {PREREQ_LABEL[prereq]}（{len(group)} 个文件）")
        L.append("")
        L.append("| 文件 | 大小 | sha256（本档字节） | 产生方式 / 命令 |")
        L.append("| --- | --- | --- | --- |")
        for it in group:
            L.append(f"| `{it['path']}` | {it['bytes']:,} B | `{it['sha256'][:16]}…` | {it['produce']} |")
        L.append("")

    L.append("## 2. 未纳入本档的登记项（如实登记，不假装覆盖）")
    L.append("")
    if skipped:
        for m in skipped:
            L.append(f"- {m}")
        L.append("")
    else:
        L.append("（无：登记表内所有产物都已纳入本档。）")
        L.append("")
    L.append("干净检出下 `logs/` 整体不存在（已 gitignore）——运行态证据天然本机独有，"
             "不是打包遗漏；上列其余缺席项说明对应产物尚未生成。")
    L.append("")

    L.append("## 3. 完整性")
    L.append("")
    L.append("各文件 sha256 见 `CHECKSUMS.sha256`；校验：")
    L.append("")
    L.append("```bash")
    L.append("cd <本档目录> && sha256sum -c CHECKSUMS.sha256")
    L.append("```")
    L.append("")
    L.append("> 本档由 `scripts/pack_evidence.py` 自动生成；`_archive/` 已 gitignore，"
             "故快照不会反过来污染仓库。凭据类文件（`.env`、`data/`、`backup/`）**从不**纳入。")
    L.append("")
    return "\n".join(L)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="证据链一键打包（零凭据、零网络、仅 stdlib）")
    ap.add_argument("--out", default=str(REPO / "_archive" / "evidence"),
                    help="输出父目录（默认 _archive/evidence，已 gitignore）")
    ap.add_argument("--zip", action="store_true", help="额外产出同名 .zip")
    ap.add_argument("--include-local-evidence", action="store_true",
                    help="纳入 logs/ 下的本机运行态证据（审计/告警运行史）——含查询内容与租户标识，"
                         "默认排除以免误发")
    ap.add_argument("--allow-stale", action="store_true",
                    help="随附报告与语料不同代时仍然出包（档内会显著标注为过期快照；CI 从不使用本开关）")
    a = ap.parse_args(argv)

    # 自检挡在**任何产物落地之前**：拒绝时不留半个归档目录，避免"被拒的包"被误当成品分发。
    gen = same_generation()
    if not gen["ok"] and not a.allow_stale:
        print(f"FAIL 拒绝出包：{gen['detail']}", file=sys.stderr)
        for line in gen["diffs"]:
            print(f"     {line}", file=sys.stderr)
        print("     本档的承诺是「每个数字都能在这里复核」；报告过期时出包，"
              "等于把旧结论盖章成现行结论。", file=sys.stderr)
        print("     修法：cd offline && python eval/build_golden.py && python eval/evaluate.py，"
              "再 python provenance.py --stamp", file=sys.stderr)
        print("     确实要留一份过期快照（例如取证对比）：加 --allow-stale，档内会显著标注。",
              file=sys.stderr)
        return 1

    mode = detect_mode()
    git = git_facts()
    ts = datetime.now(timezone.utc).astimezone()
    name = f"{ts.strftime('%Y%m%d-%H%M%S')}-{git['sha'][:8]}-{mode}"
    dest = Path(a.out).resolve() / name
    if dest.exists():
        print(f"FAIL 目标已存在：{dest}", file=sys.stderr)
        return 1

    items, skipped = collect(a.include_local_evidence)
    if not items:
        print("FAIL 未收集到任何产物——确认在仓库内运行本脚本", file=sys.stderr)
        return 1

    meta = {
        "generated_at": ts.isoformat(timespec="seconds"),
        "git": git,
        "mode": mode,
        "python": sys.version.split()[0],
        "host_os": f"{sys.platform}",
        "includes_local_evidence": a.include_local_evidence,
        "same_generation": gen,
    }
    prov = load_provenance()

    # 只复制入库范围内的产物（REGISTRY 已限定；logs/ 仅在显式开启时纳入）
    dest.mkdir(parents=True)
    copied: list[str] = []
    for it in items:
        src = REPO / it["path"]
        out = dest / it["path"]
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, out)
        copied.append(it["path"])

    # 档内文本一律显式 LF：本档要在任意平台被读、被 sha256sum 校验，
    # 而 Windows 的 write_text 会写成 CRLF——`sha256sum -c` 会把行尾的 \r 当成文件名的一部分而全项失败
    # （实测踩到过：35 项全部 "No such file or directory"）。
    (dest / "MANIFEST.md").write_bytes(render_manifest(meta, items, skipped, prov).encode("utf-8"))
    (dest / "MANIFEST.json").write_bytes(
        (json.dumps({"meta": meta, "artifacts": items, "skipped": skipped, "provenance": prov},
                    ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    (dest / "CHECKSUMS.sha256").write_bytes(
        "".join(f"{it['sha256']}  {it['path']}\n" for it in items).encode("utf-8"))

    if a.zip:
        zpath = dest.with_suffix(".zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as zf:
            for p in sorted(dest.rglob("*")):
                if p.is_file():
                    zf.write(p, p.relative_to(dest.parent).as_posix())

    print(f"OK   证据快照 → {dest}")
    print(f"     git_sha={git['sha'][:8]}{'（工作区脏）' if git['dirty'] else ''} ｜ mode={mode}"
          f" ｜ {len(copied)} 个产物" + (f" ｜ 跳过 {len(skipped)} 项（见 MANIFEST §2）" if skipped else ""))
    print(f"     同代自检：{'通过（随附报告与语料同代）' if gen['ok'] else '未通过——本档为过期快照'}")
    print(f"     入口文档 → {dest / 'MANIFEST.md'}")
    if a.zip:
        print(f"     压缩包   → {dest.with_suffix('.zip')}")
    if not gen["ok"]:
        print("WARN 本档为显式放行的过期快照（--allow-stale）：报告数字非现行结论，勿作能力引用",
              file=sys.stderr)
    if a.include_local_evidence:
        print("WARN 本档含本机运行态证据（logs/，含查询内容与租户标识）——勿原样公开分发", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
