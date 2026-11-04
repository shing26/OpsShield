"""反馈复核队列——把"人说答错了"变成一份可排序、可复核的队列。

为什么存在：`/api/v1/copilot/feedback` 收下反馈已经有一段时间，但收下之后**没有人读**——
审计里只有 `ev=feedback` 一行行散落的 `fp + verdict + note`，而 `OPS.md` §5.1 的
「复盘 → 知识回灌」触发线（同一根因被人工复盘 ≥2 次）需要一个"哪些问题被反复判负"的视图。
抓取侧看 Prometheus 的 `aiops_feedback_total{verdict}` 只能知道总量，答不出"是哪几条"。
本工具补的就是这一段：**读 → 归并 → 排序 → 交给人**。

三条设计边界（写给读这份代码的人）：

1. **只排序，不处置**。输出是一份给人工的队列，工具不修改语料、不改排序权重、不自动重训。
   与 `postmortem_draft.py` 同一条红线：结论必须经人，机器只提供证据与优先级建议。
2. **按 fingerprint 归并，而不是按某次生成**。审计里 `ev=feedback` 只有 `fp`，没有与
   `ev=chat` 的 request_id 关联（反馈是另一次 HTTP 请求，自带 request_id）。所以"被否的是
   哪一次生成"在数据上不可知——本工具**不假装知道**：它在指纹粒度呈现"这个问题被否了几次"，
   并把该指纹下的历史提问与系统行为（作答/拒答、降级档位）作为上下文一并列出。
3. **引用只来自告警台账**。手工提问没有台账行，故 `ref_docs` 为空是预期，不是缺失；
   报表里显式写"无引用清单"而不是留白。

q 的语义提醒：`fp` 是归一化后的指纹（时间戳/UUID/数字都被掩码），所以同一个 fp 可能对应
"订单 123 超时"和"订单 456 超时"两种问法。这正是"同根因 ≥2 次"能被数出来的原因，
但也意味着 fp 级统计**不等于**单次生成的准确率。

用法：
    python offline/feedback_review.py                        # 写 offline/staging/feedback/queue_<date>.md
    python offline/feedback_review.py --stdout               # 只打印
    python offline/feedback_review.py --json --stdout        # 机器可读（给看板/脚本）
    python offline/feedback_review.py --audit logs --out /tmp

退出码：0 成功（队列可以为空）；2 审计里没有任何反馈行。
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

OFFLINE = Path(__file__).resolve().parent
REPO_ROOT = OFFLINE.parent

# 审计读取口径复用复盘草稿工具（坏行跳过、轮转片按名排序）——两个离线工具必须看到同一份事实，
# 各抄一份迟早会漂（一边跳过坏行、一边炸掉，同一天的审计会得出两种结论）。
from postmortem_draft import audit_paths, load_jsonl  # noqa: E402

DEFAULT_AUDIT = REPO_ROOT / "logs" / "audit.jsonl"
DEFAULT_LEDGER = REPO_ROOT / "logs" / "alert-bridge.jsonl"
STAGING_DIR = OFFLINE / "staging" / "feedback"

# 单条提问在报表里最多展示这么多字符；审计行本身已截到 200。
QUERY_SHOW = 120
# 每个指纹最多列几种问法，避免一个高频指纹把报表撑爆。
QUERY_MAX = 3

# 优先级：数字越小越先看。名字是给人读的，理由写在 render 的说明里。
P_REPEAT = 1      # 同一指纹 down >= 2：复盘触发线的直接候选
P_ANSWERED = 2    # 只否了一次，但当时系统给了答案（错答比拒答更贵）
P_REFUSED = 3     # 只否了拒答：可能是过度拒答，也可能是问题超出语料


def _num(value: object) -> int:
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0


def _iso(ts: object) -> str:
    try:
        return datetime.fromtimestamp(_num(ts) / 1000).strftime("%Y-%m-%d %H:%M:%S")
    except (OverflowError, OSError, ValueError):
        return "—"


def _date(ts: object) -> str:
    iso = _iso(ts)
    return iso[:10] if iso != "—" else "unknown"


def _cell(value: object) -> str:
    if value is None or value == "":
        return "—"
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def _short(text: str, limit: int = QUERY_SHOW) -> str:
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _unique(values: list[str]) -> list[str]:
    out: list[str] = []
    for v in values:
        if v and v not in out:
            out.append(v)
    return out


@dataclass
class Item:
    """一个指纹的复核卡片：反馈票数 + 该指纹下系统行为的上下文。"""

    fp: str
    down: int = 0
    up: int = 0
    unknown: int = 0
    notes: list[str] = field(default_factory=list)
    down_ts: list[int] = field(default_factory=list)
    answered: int = 0
    refused: int = 0
    last_ask_ts: int = 0
    queries: list[str] = field(default_factory=list)
    traces: list[str] = field(default_factory=list)
    sources: list[str] = field(default_factory=list)
    degrade: list[str] = field(default_factory=list)
    ref_docs: list[str] = field(default_factory=list)
    alert_ids: list[str] = field(default_factory=list)

    def add_feedback(self, row: dict) -> None:
        verdict = str(row.get("verdict") or "")
        if verdict == "down":
            self.down += 1
            self.down_ts.append(_num(row.get("ts")))
        elif verdict == "up":
            self.up += 1
        else:
            # 未知 verdict 只计数，不归类——DTO 侧已收口，走到这里说明日志被人改过或版本不齐。
            self.unknown += 1
        note = " ".join(str(row.get("note") or "").split())
        if note:
            self.notes.append(note)

    def add_chat(self, row: dict) -> None:
        self.last_ask_ts = max(self.last_ask_ts, _num(row.get("ts")))
        if row.get("refused") is True:
            self.refused += 1
        else:
            self.answered += 1
        q = _short(str(row.get("q") or ""))
        if q and q not in self.queries and len(self.queries) < QUERY_MAX:
            self.queries.append(q)
        self.traces = _unique(self.traces + [str(row.get("trace_id") or "")])
        self.sources = _unique(self.sources + [str(row.get("source") or "")])
        self.degrade = _unique(self.degrade + [str(row.get("degrade_level") or "")])

    def add_ledger(self, row: dict) -> None:
        self.ref_docs = _unique(self.ref_docs + [str(d) for d in (row.get("ref_docs") or [])])
        self.alert_ids = _unique(self.alert_ids + [str(row.get("alert_id") or "")])

    @property
    def priority(self) -> int:
        if self.down >= 2:
            return P_REPEAT
        if self.answered:
            return P_ANSWERED
        return P_REFUSED

    @property
    def label(self) -> str:
        return {
            P_REPEAT: "P1 重复判负",
            P_ANSWERED: "P2 作答被否",
            P_REFUSED: "P3 仅拒答被否",
        }[self.priority]

    @property
    def mixed(self) -> bool:
        """同一个问题既有 up 又有 down：判断有分歧，复核时别只看票数。"""
        return self.up > 0 and self.down > 0

    @property
    def last_down_ts(self) -> int:
        return max(self.down_ts) if self.down_ts else 0


def collect(audit_rows: list[dict], ledger_rows: list[dict]) -> tuple[list[Item], dict]:
    """归并成指纹级条目。返回 (队列, 统计)。只有 down>0 的指纹进队列。"""
    items: dict[str, Item] = {}
    feedback_events = 0
    for row in audit_rows:
        if row.get("ev") != "feedback":
            continue
        fp = str(row.get("fp") or "")
        if not fp:
            # 无指纹的反馈无法归档：计数后丢弃，而不是塞进一个 "unknown" 桶里假装归并过。
            continue
        feedback_events += 1
        items.setdefault(fp, Item(fp)).add_feedback(row)

    # 上下文：该指纹下的历史请求（按时间正序，问法取最早的几种更可读）
    for row in sorted((r for r in audit_rows if r.get("ev") == "chat"), key=lambda r: _num(r.get("ts"))):
        item = items.get(str(row.get("fp") or ""))
        if item is not None:
            item.add_chat(row)

    for row in ledger_rows:
        item = items.get(str(row.get("fp") or ""))
        if item is not None:
            item.add_ledger(row)

    queue = [it for it in items.values() if it.down > 0]
    queue.sort(key=lambda it: (it.priority, -it.down, -it.last_down_ts))

    # "没有对应 chat 行"的反馈指纹：审计轮转/保留期外，或请求来自另一份审计。
    # 这类指纹仍进队列（人需要知道它被判负了），但上下文为空——必须显式标出来。
    unmatched = sum(1 for it in queue if it.answered + it.refused == 0)

    timestamps = [_num(r.get("ts")) for r in audit_rows
                  if r.get("ev") == "feedback" and r.get("ts") is not None]
    stats = {
        "feedback_events": feedback_events,
        "fingerprints": len(items),
        "down": sum(it.down for it in items.values()),
        "up": sum(it.up for it in items.values()),
        "unknown_verdict": sum(it.unknown for it in items.values()),
        "queue": len(queue),
        "unmatched_context": unmatched,
        "window_start": _iso(min(timestamps)) if timestamps else "—",
        "window_end": _iso(max(timestamps)) if timestamps else "—",
        "last_date": _date(max(timestamps)) if timestamps else "unknown",
    }
    return queue, stats


def suspect_docs(queue: list[Item]) -> list[dict]:
    """被否指纹引用过的文档，按"判负票数"排序。

    这是相关性排序，不是根因判定：一篇文档出现在被判负的答案里，可能是它本身写错了、
    也可能是该问题根本没有对应文档（检索只能拿相邻的凑）。报表里两种可能都指向复核，
    但结论要人给。
    """
    agg: dict[str, dict] = {}
    for item in queue:
        for doc in item.ref_docs:
            slot = agg.setdefault(doc, {"doc": doc, "down": 0, "fingerprints": 0})
            slot["down"] += item.down
            slot["fingerprints"] += 1
    return sorted(agg.values(), key=lambda d: (-d["down"], -d["fingerprints"], d["doc"]))


def _render_item(idx: int, item: Item) -> list[str]:
    lines: list[str] = []
    add = lines.append
    votes = f"down {item.down} / up {item.up}"
    add(f"### {idx}. `{item.fp}` — {item.label}（{votes}）")
    add("")
    if item.answered + item.refused == 0:
        add("- **审计里没有该指纹的请求行**（超出保留期/另一份审计）：只剩票数，按反馈原样复核。")
    else:
        add(f"- 系统当时：作答 {item.answered} 次、拒答 {item.refused} 次"
            f"；最近一次请求 {_iso(item.last_ask_ts)}")
        if item.degrade:
            add(f"- 降级档位：{'、'.join(item.degrade)}")
        if item.sources:
            add(f"- 来源：{'、'.join(item.sources)}")
        if item.traces:
            add(f"- trace_id：{'、'.join(f'`{t}`' for t in item.traces)}")
        if item.queries:
            add("- 提问（该指纹下最早的几种问法，数字/ID 已被指纹归一化掩码）：")
            for q in item.queries:
                add(f"  - `{q}`")
    if item.mixed:
        add("- **同时有 up 票**：同一问题判断有分歧，复核时先看两边的具体理由，别只看票数。")
    if item.notes:
        add("- 人写的原因：")
        for note in item.notes:
            add(f"  - {note}")
    if item.ref_docs:
        add(f"- 被引用的文档：{'、'.join(f'`{d}`' for d in item.ref_docs)}")
        if item.alert_ids:
            add(f"- 告警：{'、'.join(f'`{a}`' for a in item.alert_ids)}")
    else:
        add("- 引用清单：无（手工提问没有告警台账行；或该次投递未开启台账）")
    add("")
    return lines


def render(queue: list[Item], stats: dict, suspects: list[dict]) -> str:
    lines: list[str] = []
    add = lines.append

    add("# 反馈复核队列")
    add("")
    add(f"> 由 `offline/feedback_review.py` 生成；数据窗口 {stats['window_start']} → "
        f"{stats['window_end']}。")
    add("> 本文件是**派生报表**（落在 `offline/staging/`，不入库）：可以随时重生成，"
        "不要在上面做人工批注。")
    add("")
    add("## 统计")
    add("")
    add(f"- 反馈事件：{stats['feedback_events']} 条"
        f"（down {stats['down']} / up {stats['up']}"
        + (f" / 未知 {stats['unknown_verdict']}" if stats["unknown_verdict"] else "") + "）")
    add(f"- 涉及指纹：{stats['fingerprints']} 个；进队列（有 down）：{stats['queue']} 条")
    if stats["unmatched_context"]:
        add(f"- 其中 {stats['unmatched_context']} 个指纹在审计里找不到对应请求行"
            "（超出保留期或来自另一份审计）：只剩票数，没有上下文。")
    add("")

    if not queue:
        add("队列为空：窗口内没有 `down` 反馈。"
            "Prometheus 侧的 0 基线见 `aiops_feedback_total{verdict=\"down\"}`。")
        add("")
        add(_boundaries())
        return "\n".join(lines)

    add("## 复核队列")
    add("")
    add("优先级不是准确率结论，是复核顺序建议：P1 = 同一指纹被否 ≥2 次（`OPS.md` §5.1 复盘"
        "触发线的直接候选）；P2 = 只否过一次但当时系统给了答案；P3 = 只否了拒答。")
    add("")
    add("| 优先级 | 指纹 | down/up | 作答/拒答 | 最近判负 | 最近提问 |")
    add("|---|---|---|---|---|---|")
    for item in queue:
        recent_q = item.queries[0] if item.queries else "（无请求行）"
        add("| {p} | `{fp}` | {d}/{u} | {a}/{r} | {ts} | {q} |".format(
            p=item.label,
            fp=item.fp,
            d=item.down,
            u=item.up,
            a=item.answered,
            r=item.refused,
            ts=_iso(item.last_down_ts),
            q=_cell(recent_q),
        ))
    add("")

    if suspects:
        add("## 可疑文档（相关性排序，不是根因判定）")
        add("")
        add("这些文档出现在被判负的答案里。两种可能都指向复核：文档本身写错，或该问题根本没有"
            "对应文档（检索只能拿相邻的凑）。改语料前请先看下面对应的具体指纹。")
        add("")
        add("| 文档 | 关联判负票数 | 涉及指纹数 |")
        add("|---|---|---|")
        for doc in suspects[:10]:
            add(f"| `{doc['doc']}` | {doc['down']} | {doc['fingerprints']} |")
        add("")

    add("## 逐条证据")
    add("")
    for idx, item in enumerate(queue, 1):
        lines.extend(_render_item(idx, item))

    add(_boundaries())
    return "\n".join(lines)


def _boundaries() -> str:
    return "\n".join([
        "## 怎么读这份队列（边界）",
        "",
        "- **指纹 ≠ 某次生成**：`ev=feedback` 只有 `fp`，与 `ev=chat` 的 request_id 没有关联"
        "（反馈是另一次请求）。所以这里答的是「这个问题被否了几次」，不是「哪一次答案错了」。",
        "- **`fp` 是归一化指纹**：时间戳/UUID/数字都被掩码，同根因的不同问法会落到同一指纹——"
        "这正是「≥2 次」能被数出来的原因，也意味着票数是问题级的。",
        "- **拒答也可能被判负**：P3 不一定是错答，可能是过度拒答（该答没答）或问题超出语料。"
        "两类后续动作不同：前者调阈值/补文档，后者记录为已知缺口。",
        "- **处置是人**：改语料 / 调排序 / 补 runbook 都由人决定；本工具不写语料、不改权重。",
        "- 复核后要沉淀成知识：用 `offline/postmortem_draft.py` 起草复盘，或直接补"
        " `offline/corpus/` 下对应文档，再走 `POST /api/v1/admin/reingest`。",
        "",
    ])


def render_json(queue: list[Item], stats: dict, suspects: list[dict]) -> str:
    payload = {
        "stats": stats,
        "queue": [
            {
                "fingerprint": item.fp,
                "priority": item.priority,
                "label": item.label,
                "down": item.down,
                "up": item.up,
                "mixed": item.mixed,
                "answered": item.answered,
                "refused": item.refused,
                "last_ask": _iso(item.last_ask_ts) if item.last_ask_ts else None,
                "last_down": _iso(item.last_down_ts) if item.last_down_ts else None,
                "queries": item.queries,
                "traces": item.traces,
                "sources": item.sources,
                "degrade": item.degrade,
                "notes": item.notes,
                "ref_docs": item.ref_docs,
                "alert_ids": item.alert_ids,
            }
            for item in queue
        ],
        "suspect_docs": suspects,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def default_name(stats: dict, suffix: str) -> str:
    """文件名由数据窗口的日期决定，而不是今天：同一份审计重跑得到同名文件（可复现）。"""
    return f"queue_{stats['last_date']}{suffix}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="把审计里的反馈归并成一份人工复核队列（只读审计，不改语料）")
    parser.add_argument("--audit", default=str(DEFAULT_AUDIT),
                        help="审计文件、目录或通配符（默认 logs/audit.jsonl）")
    parser.add_argument("--alert-ledger", default=str(DEFAULT_LEDGER),
                        help="告警台账（默认 logs/alert-bridge.jsonl；缺失则无可疑文档段）")
    parser.add_argument("--out", default=str(STAGING_DIR),
                        help="输出目录（默认 offline/staging/feedback）")
    parser.add_argument("--stdout", action="store_true", help="只打印，不落盘")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON（配合 --stdout 或落盘）")
    args = parser.parse_args(argv)

    audit_rows, bad = load_jsonl(audit_paths(args.audit))
    if not any(r.get("ev") == "feedback" for r in audit_rows):
        print(f"审计里没有任何 ev=feedback 行（读入 {len(audit_rows)} 行，坏行 {bad}）。"
              "先确认反馈端点有人在用，或用 --audit 指向正确的日志目录。", file=sys.stderr)
        return 2

    ledger_rows, _ = load_jsonl([Path(args.alert_ledger)])
    queue, stats = collect(audit_rows, ledger_rows)
    suspects = suspect_docs(queue)
    if bad:
        print(f"warn: 跳过 {bad} 行无法解析的审计行", file=sys.stderr)

    if args.json:
        text, suffix = render_json(queue, stats, suspects), ".json"
    else:
        text, suffix = render(queue, stats, suspects), ".md"

    if args.stdout:
        sys.stdout.write(text + ("" if text.endswith("\n") else "\n"))
        return 0

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / default_name(stats, suffix)
    target.write_text(text, encoding="utf-8", newline="\n")
    print(f"已生成复核队列：{target}")
    print(f"  反馈 {stats['feedback_events']} 条；进队列 {stats['queue']} 个指纹；"
          f"可疑文档 {len(suspects)} 篇；窗口 {stats['window_start']} → {stats['window_end']}")
    print("  下一步：按优先级人工复核；结论用 offline/postmortem_draft.py 起草复盘，"
          "或直接补语料后 reingest。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
