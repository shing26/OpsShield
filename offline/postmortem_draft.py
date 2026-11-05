"""事故复盘草稿生成器——人机协同：机器填证据，人填判断。

为什么存在：`OPS.md` §5.1 的「复盘 → 知识回灌」触发线已兑现过一次（rb-101~105，五篇本机
真实事故），但那次是人工离线写 md。运行期仍缺一个入口：**一次已解决的 incident，怎么变成
一份可审的复盘草稿**。本工具补的就是这一段。

三条设计红线（写给读这份代码的人）：

1. **只写 staging，永不写语料**。输出默认落 `offline/staging/postmortems/`；目标路径若解析到
   `offline/corpus/` 之下，直接拒绝。语料是全系统"可信"的源头，草稿在入库前必须经人审。
2. **机器只填可核验的事实，不猜判断**。根因 / 处置 / 验证 / 教训四段一律只写 `TODO(人)`；
   生成器不会为凑一篇完整复盘而编造因果——那正是本仓库一直在防的幻觉环路。
3. **草稿自身必须过敏感信息扫描**。渲染后复用 `eval/dataset_audit.audit_text`；命中邮箱 /
   公网 IP / 凭据 / 长 hex / 手机号即拒绝写出（fail-closed），由人先脱敏或确认输入。

证据从哪来（审计行只有统计量，没有正文）：

- `logs/audit.jsonl` 的 `ev=chat` 行：按 `trace_id` 取一次 incident 的全部步骤。字段含
  `request_id`（服务端权威）、`via`、`source`、`refused`、`mode`、`refs`/`actions`（**计数**）、
  `took_ms`、`degrade_level`；**没有**答案正文与引用清单。
- `logs/alert-bridge.jsonl`（可选）：告警台账，补 `alert_id`、`status`、`refs`（chunk id）、
  `ref_docs`、`cache_hit`、`deduplicated`。
- 同一份审计里的 `ev=feedback`（按 `fp` 关联）与 `ev=degrade_transition`（窗口内）。

用法：
    python offline/postmortem_draft.py --trace-id am-<32hex>
    python offline/postmortem_draft.py --trace-id agent-<id> --audit logs --stdout
    python offline/postmortem_draft.py --trace-id ... --out offline/staging/postmortems

退出码：0 成功；2 找不到该 trace 的审计行；3 拒绝写出（语料目录 / 敏感信息）。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STAGING_DIR = REPO_ROOT / "offline" / "staging" / "postmortems"
DEFAULT_AUDIT = REPO_ROOT / "logs" / "audit.jsonl"
DEFAULT_LEDGER = REPO_ROOT / "logs" / "alert-bridge.jsonl"

# 语料目录（写入守卫的判据）。resolved 之后做前缀比较，避免 `..` 绕过。
CORPUS_ROOT = (REPO_ROOT / "offline" / "corpus").resolve()

TODO = "TODO(人)"

# 单行 query 在证据表里最多展示这么多字符；审计行本身已截到 200，这里只为排版。
QUERY_SHOW = 200


def _iso(ts: object) -> str:
    """审计 ts 是 epoch ms；渲染成本地时间。缺失时返回破折号，不猜。"""
    try:
        return datetime.fromtimestamp(int(ts) / 1000).strftime("%Y-%m-%d %H:%M:%S")
    except (TypeError, ValueError, OSError):
        return "—"


def _cell(value: object) -> str:
    """表格单元格：空值统一破折号，竖线转义（query/note 里可能带）。"""
    if value is None or value == "":
        return "—"
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def _bool_cell(value: object) -> str:
    if value is True:
        return "拒答"
    if value is False:
        return "作答"
    return "—"


def load_jsonl(paths: list[Path]) -> tuple[list[dict], int]:
    """宽容读取：坏行计数后跳过（与 scripts/audit_timeline.py 同一口径）。"""
    rows: list[dict] = []
    bad = 0
    for path in paths:
        try:
            text = path.read_text(encoding="utf-8")
        except FileNotFoundError:
            continue
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            if isinstance(obj, dict):
                rows.append(obj)
            else:
                bad += 1
    return rows, bad


def audit_paths(arg: str) -> list[Path]:
    """`--audit` 可以是文件、目录（取 audit*.jsonl 轮转片）或带通配符的路径。"""
    p = Path(arg)
    if p.is_dir():
        return sorted(p.glob("audit*.jsonl"))
    if any(ch in arg for ch in "*?["):
        return sorted(Path().glob(arg))
    return [p]


def select_incident(rows: list[dict], trace_id: str) -> list[dict]:
    """一个 incident = 共享 trace_id 的 ev=chat 行；按 (ts, request_id) 稳定排序。

    去重键含 (ts, request_id, q)：Java 单测会把真实 AuditService 的写入打到同一个
    logs/audit.jsonl，重启后又出现重复的 seq=1；但它们没有目标 trace_id，天然被过滤。
    """
    kept = [
        r for r in rows
        if r.get("ev") == "chat" and str(r.get("trace_id") or "") == trace_id
    ]
    kept.sort(key=lambda r: (_num(r.get("ts")), str(r.get("request_id") or "")))
    out: list[dict] = []
    seen: set[tuple] = set()
    for r in kept:
        key = (r.get("ts"), r.get("request_id"), r.get("q"))
        if key in seen:
            continue
        seen.add(key)
        out.append(r)
    return out


def _num(value: object) -> int:
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0


def match_ledger(rows: list[dict], trace_id: str) -> list[dict]:
    """告警台账按 `incident_id`（桥经 X-Trace-Id 送出的同一个值）或 `trace_id` 关联。"""
    return [
        r for r in rows
        if str(r.get("incident_id") or "") == trace_id
        or str(r.get("trace_id") or "") == trace_id
    ]


def related_events(rows: list[dict], chat_rows: list[dict]) -> tuple[list[dict], list[dict]]:
    """同一份审计里的人工反馈（按 fp）与窗口内的降级切换。"""
    fps = {str(r.get("fp")) for r in chat_rows if r.get("fp")}
    feedback = [
        r for r in rows
        if r.get("ev") == "feedback" and str(r.get("fp") or "") in fps
    ]
    stamps = [_num(r.get("ts")) for r in chat_rows if r.get("ts") is not None]
    lo, hi = (min(stamps), max(stamps)) if stamps else (0, 0)
    transitions = [
        r for r in rows
        if r.get("ev") == "degrade_transition" and lo <= _num(r.get("ts")) <= hi
    ]
    feedback.sort(key=lambda r: _num(r.get("ts")))
    transitions.sort(key=lambda r: _num(r.get("ts")))
    return feedback, transitions


def _unique(values: list[str]) -> list[str]:
    out: list[str] = []
    for v in values:
        if v and v not in out:
            out.append(v)
    return out


def render(trace_id: str, chat_rows: list[dict], ledger: list[dict],
           feedback: list[dict], transitions: list[dict]) -> str:
    """把证据渲染成草稿。判断类小节只写 TODO——这是本工具最重要的不变式。"""
    date = _iso(chat_rows[0].get("ts"))[:10] if chat_rows else "unknown"
    first_query = str(chat_rows[0].get("q") or "") if chat_rows else ""

    refs = _unique([str(c) for row in ledger for c in (row.get("refs") or [])])
    ref_docs = _unique([str(d) for row in ledger for d in (row.get("ref_docs") or [])])
    alert_ids = _unique([str(r.get("alert_id") or "") for r in ledger])
    names = _unique([str(r.get("alertname") or "") for r in ledger])
    statuses = _unique([str(r.get("status") or "") for r in ledger])
    error_codes = _unique([str(r.get("error_code") or "") for r in ledger])

    lines: list[str] = []
    add = lines.append

    add("---")
    add("doc_id: __TODO__")
    add("service: __TODO__")
    add("env: local")
    add("auth_level: 1")
    add("error_codes: []")
    add(f"date: {date}")
    add("severity: __TODO__")
    add("---")
    add(f"<!-- 由 offline/postmortem_draft.py 生成；trace_id={trace_id}；"
        f"审计行 {len(chat_rows)} 条。")
    add("     这是草稿：机器只填证据，根因/处置/验证/教训留给人。"
        "未经人审不得进入 offline/corpus/。 -->")
    add("")
    add(f"# {TODO}：一句话说清这次事故（{date} 真实事故复盘草稿）")
    add("")

    add("## 机器填写的证据（生成器直出，勿手改）")
    add("")
    add("| # | 时间 | request_id | via | source | 结果 | mode | refs | actions | took_ms | 降级 |")
    add("|---|---|---|---|---|---|---|---|---|---|---|")
    for i, row in enumerate(chat_rows, 1):
        add(
            "| {n} | {ts} | {rid} | {via} | {src} | {res} | {mode} | {refs} | {actions} | {ms} | {deg} |".format(
                n=i,
                ts=_iso(row.get("ts")),
                rid=_cell(row.get("request_id")),
                via=_cell(row.get("via")),
                src=_cell(row.get("source")),
                res=_bool_cell(row.get("refused")),
                mode=_cell(row.get("mode")),
                refs=_cell(row.get("refs")),
                actions=_cell(row.get("actions")),
                ms=_cell(row.get("took_ms")),
                deg=_cell(row.get("degrade_level")),
            )
        )
    add("")
    add("> `refs` / `actions` 是**计数**，不是清单——审计行只落统计量；引用清单看下方台账段。")
    add("> `request_id` 由服务端权威发放，`trace_id` 是调用方关联键；两者分工见 ADR-0007 修订注。")
    add("")
    add(f"**原始问题**（审计行 `q`，已按 {QUERY_SHOW} 字符截断）：")
    add("")
    add("~~~text")
    add(first_query[:QUERY_SHOW] or "（审计行未记录 query）")
    add("~~~")
    add("")

    add("**告警台账**（`logs/alert-bridge.jsonl`）")
    add("")
    if ledger:
        add(f"- alert_id：{'、'.join(alert_ids) or '—'}")
        add(f"- alertname：{'、'.join(names) or '—'}")
        add(f"- status：{'、'.join(statuses) or '—'}")
        add(f"- error_code：{'、'.join(error_codes) or '—'}")
        add(f"- 引用 chunk：{'、'.join(refs) or '—'}")
        add(f"- 引用文档：{'、'.join(ref_docs) or '—'}")
    else:
        add("- 未找到该 incident 的台账行（可能来自手工请求，或台账未开启）。"
            "**不推断**引用清单。")
    add("")

    add("**人工反馈**（`ev=feedback`，按 fingerprint 关联）")
    add("")
    if feedback:
        for row in feedback:
            verdict = str(row.get("verdict") or "—")
            note = str(row.get("note") or "").strip()
            add(f"- {verdict}" + (f"：{note}" if note else ""))
    else:
        add("- 无。")
    add("")

    add("**降级切换**（`ev=degrade_transition`，incident 窗口内）")
    add("")
    if transitions:
        for row in transitions:
            add(f"- {row.get('from', '?')} → {row.get('to', '?')}"
                f"（cause={row.get('cause', '?')}，{_iso(row.get('ts'))}）")
    else:
        add("- 无。")
    add("")

    for title, hint in (
        ("事故摘要", "现象、影响面、谁发现的"),
        ("时间线", "人做了什么、什么时候恢复的（机器只填了请求与反馈时刻）"),
        ("根因", "为什么会发生；没有证据就写“未定位”，不要猜"),
        ("处置", "实际做了什么、顺序、为什么是这个顺序"),
        ("验证", "怎么确认恢复了；用什么证据"),
        ("教训", "防复发项：文档 / 告警 / 流程 / 代码"),
    ):
        add(f"## {title}")
        add("")
        add(f"{TODO}：{hint}。")
        add("")

    add("## 入库前检查清单（人）")
    add("")
    add("- [ ] 上面六个 TODO 已由人写完，且没有把模型猜测写成事实")
    add("- [ ] 已去敏：邮箱 / 公网 IP / 凭据 / 长 hex / 手机号（`python eval/dataset_audit.py` 会复扫）")
    add("- [ ] `service` / `error_codes` / `severity` 已填，`doc_id` 改成唯一的 `rb-NNN` 或 `pm-NNN`")
    add("- [ ] 文件名改成 `rb-NNN-<slug>.md` 并移入 `offline/corpus/runbooks/`（或 postmortems）")
    add("- [ ] `python chunkers/build_chunks.py`")
    add("- [ ] `python eval/build_golden.py && python eval/evaluate.py`（会重打 provenance）")
    add("- [ ] 更新 doc_numbers 登记的数字（复盘/Runbook 篇数、chunks 行数）")
    add("- [ ] `POST /api/v1/admin/reingest`")
    add("")
    return "\n".join(lines)


def guard_target(target: Path) -> str | None:
    """写入守卫：语料目录一律拒绝。这是本工具唯一不可协商的边界。

    返回错误话术（None 表示放行），由 main 统一打印并返回退出码——不在这里 SystemExit，
    是为了让调用方（测试、将来的编排脚本）能按返回值处理，而不是被迫捕获异常。
    """
    resolved = target.resolve()
    try:
        resolved.relative_to(CORPUS_ROOT)
    except ValueError:
        return None
    return (
        f"拒绝写入：{resolved} 在语料目录 {CORPUS_ROOT} 之下。\n"
        "草稿只能落到 staging，经人审后由人移入语料——这是「结论→语料」不自动闭环的设计红线。"
    )


def sensitive_findings(text: str, name: str, trace_id: str) -> list[dict]:
    """复用评测侧的敏感信息扫描（与 dataset_audit 同一套规则）。

    trace_id 本身可能是 64 位 hex（调用方关联键允许 8–64 位），会被 `long_hex_or_jwt`
    误判；把 trace_id 从命中里剔除——它是关联键，不是凭据。
    """
    eval_dir = Path(__file__).resolve().parent / "eval"
    if str(eval_dir) not in sys.path:
        sys.path.insert(0, str(eval_dir))
    import dataset_audit  # noqa: PLC0415 - 延迟导入，避免离线工具启动即拉起 eval 依赖

    findings = dataset_audit.audit_text(name, text)
    return [f for f in findings if trace_id not in str(f.get("sample", ""))]


def default_name(trace_id: str, chat_rows: list[dict]) -> str:
    """默认文件名刻意**不匹配** chunker 的 doc_id 正则 `[a-z]+-\\d+`。

    `markdown_tree_chunker.py` 从文件名前缀取 doc_id；如果草稿叫 `rb-2026-...`，两个草稿
    就会共享 doc_id 而互相覆盖。`draft_` 前缀让"忘了改名就入库"在 build_chunks 阶段就失败。
    """
    date = _iso(chat_rows[0].get("ts"))[:10] if chat_rows else "unknown"
    slug = re.sub(r"[^A-Za-z0-9_-]+", "_", trace_id)[:32] or "incident"
    return f"draft_{date}_{slug}.md"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="从审计行生成一份待审的事故复盘草稿（只写 staging，不写语料）")
    parser.add_argument("--trace-id", required=True,
                        help="一次 incident 的 trace_id（告警组 am-… 或 agent 的多步关联键）")
    parser.add_argument("--audit", default=str(DEFAULT_AUDIT),
                        help="审计文件、目录或通配符（默认 logs/audit.jsonl）")
    parser.add_argument("--alert-ledger", default=str(DEFAULT_LEDGER),
                        help="告警台账（默认 logs/alert-bridge.jsonl；缺失则标记'无台账'）")
    parser.add_argument("--out", default=str(STAGING_DIR),
                        help="输出目录（默认 offline/staging/postmortems）")
    parser.add_argument("--stdout", action="store_true", help="只打印到 stdout，不落盘")
    parser.add_argument("--force", action="store_true", help="覆盖同名草稿")
    args = parser.parse_args(argv)

    audit_rows, bad = load_jsonl(audit_paths(args.audit))
    chat_rows = select_incident(audit_rows, args.trace_id)
    if not chat_rows:
        print(f"找不到 trace_id={args.trace_id} 的 ev=chat 审计行"
              f"（读入 {len(audit_rows)} 行，坏行 {bad}）。", file=sys.stderr)
        return 2

    ledger_rows, _ = load_jsonl([Path(args.alert_ledger)])
    ledger = match_ledger(ledger_rows, args.trace_id)
    feedback, transitions = related_events(audit_rows, chat_rows)
    text = render(args.trace_id, chat_rows, ledger, feedback, transitions)

    name = default_name(args.trace_id, chat_rows)
    findings = sensitive_findings(text, name, args.trace_id)
    if findings:
        print("拒绝写出：草稿命中敏感信息模式（先脱敏或确认输入）：", file=sys.stderr)
        for f in findings:
            print(f"  {f['kind']} @ line {f['line']}: {f['context']}", file=sys.stderr)
        return 3

    if args.stdout:
        sys.stdout.write(text)
        return 0

    out_dir = Path(args.out)
    refused = guard_target(out_dir)
    if refused:
        print(refused, file=sys.stderr)
        return 3
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / name
    if target.exists() and not args.force:
        print(f"草稿已存在：{target}（用 --force 覆盖，或换 --out）", file=sys.stderr)
        return 2
    target.write_text(text, encoding="utf-8", newline="\n")
    print(f"已生成草稿：{target}")
    print(f"  trace_id={args.trace_id}；审计行 {len(chat_rows)}；台账 {len(ledger)}；"
          f"反馈 {len(feedback)}；降级切换 {len(transitions)}")
    print("  下一步：人写根因/处置/验证/教训，按文末检查清单入库。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
