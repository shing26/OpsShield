#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L1 审计链 WORM 完整性门禁（ADR-0028 · 盲点 6 · 2026-10-26）。

判据（fail-closed）：本地 audit.jsonl 里的**每一条 L1 事件**（ev 前缀 action_）都必须在
WORM 归档里存在逐字副本；缺任何一条即 FAIL。

为什么是逐字副本而不是"时间范围内有东西"：WORM 的价值是"出事后能回放整条链"——
proposed → confirmed → dispatched → executed 每一环都要能在不可变副本里找到，
而不是靠人相信"应该都写进去了"。

用法：
  python scripts/l1_worm_gate.py --worm-dir <WORM 归档目录> [--audit logs/audit.jsonl]
WORM 目录里放 *.jsonl（audit_offsite.sh 的落盘产物）；文件可压缩归档，本脚本只读明文行。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

L1_PREFIX = "action_"


def load_events(path: Path) -> list[dict]:
    out = []
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


def event_key(ev: dict) -> tuple:
    """事件身份：ts + ev + 关键载荷（sub/tenant/op 线索）——逐字副本对账用。"""
    payload = ev.get("q") or ev.get("fp") or ""
    return (ev.get("ts"), ev.get("ev"), ev.get("sub") or "", payload)


def check(audit: Path, worm_dir: Path) -> tuple[int, list[str]]:
    local = [e for e in load_events(audit) if str(e.get("ev", "")).startswith(L1_PREFIX)]
    worm = set()
    for f in sorted(worm_dir.glob("**/*.jsonl")):
        for e in load_events(f):
            worm.add(event_key(e))
    missing = [event_key(e) for e in local if event_key(e) not in worm]
    return (0 if not missing else 1), [
        "缺 WORM 记录: ev={} ts={} sub={}".format(k[1], k[0], k[2]) for k in missing
    ]


def main() -> int:
    ap = argparse.ArgumentParser(description="L1 审计链 WORM 完整性门禁")
    ap.add_argument("--audit", default="logs/audit.jsonl")
    ap.add_argument("--worm-dir", required=True)
    a = ap.parse_args()
    audit, worm = Path(a.audit), Path(a.worm_dir)
    if not worm.is_dir():
        print(f"FAIL: WORM 目录不存在: {worm}", file=sys.stderr)
        return 2
    code, problems = check(audit, worm)
    if code != 0:
        for p in problems:
            print(f"FAIL {p}", file=sys.stderr)
        return code
    n = len([e for e in load_events(audit) if str(e.get("ev", "")).startswith(L1_PREFIX)])
    print(f"OK L1 事件 {n} 条全部有 WORM 副本")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())