#!/usr/bin/env python3
"""门禁 6/7 观察期的收口检查：把已提交的周报/月报聚合起来判定。

为什么需要它：`gate6-7-30day-observation.md` 第 30 天的动作是"按 §4 格式追加两行复核记录"，
但"连续 30 天"和"≥24 条对照记录"如果靠人肉数周报，迟早会数错——而门禁 1 的教训正是
"看起来每天在跑"却没人核。本脚本只读已提交的 `docs/ops/slo-weekly-*.json` / `slo-monthly-*.json`，
不触网、不写文件，可随时重跑，因此也能进 CI（未到 30 天时以退出码 2 表示"证据不足"，不判红）。

判定口径（与观察文档 §0 一致）：
- 门禁 6：PASS 样本 × 2 条对照记录 ≥ 24，且**没有任何 FAIL 样本**；
- 门禁 7：覆盖天数（周报 `coverage.covered` 的并集）≥ 30，保留期 ≥ 30 天，且没有断档；
- 证据不足（未到 30 天）时给 INCOMPLETE，不冒充绿。

退出码：0 两条门禁都 GREEN；1 有硬失败（样本 FAIL / 保留期不足）；2 证据不足（还在观察期）。
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date, timedelta
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = REPO_ROOT / "docs" / "ops"
REQUIRED_RECORDS = 24
REQUIRED_DAYS = 30


def load_reports(directory: Path) -> tuple[list[dict], list[str]]:
    """读全部周报/月报 JSON。坏文件不跳过——它是证据链的一部分，读不了就是红。"""
    reports: list[dict] = []
    errors: list[str] = []
    for path in sorted(directory.glob("slo-*.json")):
        try:
            reports.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, json.JSONDecodeError) as exc:
            errors.append(f"{path.name}: {exc}")
    return reports, errors


def aggregate(reports: list[dict]) -> dict:
    """多份报表求并集：同一天/同一个样本文件只算一次。"""
    days: set[str] = set()
    retention: float | None = None
    samples: dict[str, dict] = {}
    for r in reports:
        cov = r.get("coverage") or {}
        days.update(str(d) for d in cov.get("covered") or [])
        if cov.get("retention_days") is not None:
            retention = max(retention or 0, float(cov["retention_days"]))
        for s in r.get("samples") or []:
            name = str(s.get("file") or "")
            if name:
                samples[name] = s  # 后读到的覆盖先读到的：同一文件内容相同
    ordered = sorted(days)
    gaps = _gaps(ordered)
    passed = [s for s in samples.values() if s.get("verdict") == "PASS"]
    failed = [s for s in samples.values() if s.get("verdict") == "FAIL"]
    unproven = [s for s in samples.values() if s.get("verdict") not in ("PASS", "FAIL")]
    return {
        "days": ordered, "gaps": gaps, "retention_days": retention,
        "samples_total": len(samples), "samples_passed": len(passed),
        "samples_failed": len(failed), "samples_unproven": len(unproven),
        "passing_records": len(passed) * 2, "required_records": REQUIRED_RECORDS,
        "failed_files": sorted(s.get("file", "") for s in failed),
    }


def _gaps(days: list[str]) -> list[str]:
    """覆盖日里的断档（首尾之间缺的日期）。断档一天，门禁 7 的"连续"就不成立。"""
    if len(days) < 2:
        return []
    parsed = [date.fromisoformat(d) for d in days]
    missing: list[str] = []
    for a, b in zip(parsed, parsed[1:]):
        cur = a + timedelta(days=1)
        while cur < b:
            missing.append(cur.isoformat())
            cur += timedelta(days=1)
    return missing


def verdicts(agg: dict) -> tuple[str, str, list[str]]:
    """返回 (门禁6, 门禁7, 硬失败说明)。"""
    notes: list[str] = []
    if agg["samples_failed"]:
        g6 = "RED"
        notes.append(f"{agg['samples_failed']} 个样本判定 FAIL：{', '.join(agg['failed_files'])}")
    elif agg["passing_records"] >= REQUIRED_RECORDS:
        g6 = "GREEN"
    else:
        g6 = "INCOMPLETE"

    if agg["retention_days"] is not None and agg["retention_days"] < REQUIRED_DAYS:
        g7 = "RED"
        notes.append(f"Prometheus 保留期 {agg['retention_days']} 天 < {REQUIRED_DAYS} 天")
    elif agg["gaps"]:
        g7 = "RED"
        notes.append(f"抓取覆盖有断档：{'、'.join(agg['gaps'])}")
    elif len(agg["days"]) >= REQUIRED_DAYS:
        g7 = "GREEN"
    else:
        g7 = "INCOMPLETE"
    return g6, g7, notes


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="聚合门禁 6/7 观察期证据并判定（只读，不触网）")
    ap.add_argument("--dir", default=str(DEFAULT_DIR), help="周报/月报目录（默认 docs/ops）")
    ap.add_argument("--json", action="store_true", help="输出机器可读 JSON")
    args = ap.parse_args(argv)

    reports, errors = load_reports(Path(args.dir))
    if not reports and not errors:
        print("证据不足：没有找到任何 slo-*.json 周报/月报。", file=sys.stderr)
        return 2
    if errors:
        for err in errors:
            print(f"FAIL  报表读取失败：{err}", file=sys.stderr)
        return 1
    agg = aggregate(reports)
    g6, g7, notes = verdicts(agg)

    if args.json:
        print(json.dumps({"gate6": g6, "gate7": g7, "aggregate": agg, "notes": notes},
                         ensure_ascii=False, indent=2))
    else:
        print(f"门禁 6：{g6}（PASS 样本 {agg['samples_passed']}/{agg['samples_total']}；"
              f"对照记录 {agg['passing_records']}/{agg['required_records']}；"
              f"UNPROVEN {agg['samples_unproven']}）")
        print(f"门禁 7：{g7}（覆盖 {len(agg['days'])}/{REQUIRED_DAYS} 天；"
              f"保留期 {agg['retention_days']} 天；断档 {len(agg['gaps'])} 天）")
        for note in notes:
            print(f"  ! {note}")

    if "RED" in (g6, g7):
        return 1
    if (g6, g7) == ("GREEN", "GREEN"):
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
