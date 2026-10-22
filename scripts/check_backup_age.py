#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""备份新鲜度哨兵（G0，2026-10-22）。

为什么单独一个脚本而不是塞进 production_gate.sh：
  * 计划任务/cron 也要用同一个判据（"最近一次成功备份是否 <26h"），判据只能有一份；
  * 退出码要能区分"没有记录"（2，未知）与"记录过期/损坏"（1，失败）——门禁对两者的
    处置不同：G0 fast 允许"从未备份"告警但不红，G1 full 必须红。

记录由 scripts/backup.sh 在**校验通过后**写 backup/.last-success.json：
  { "epoch": 1792..., "iso": "...", "sha256": "...", "size": 1234, "verified": true }
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

MAX_AGE_HOURS = 26.0
DEFAULT = Path("backup") / ".last-success.json"


def main(argv: list[str]) -> int:
    path = Path(argv[1]) if len(argv) > 1 else DEFAULT
    if not path.is_file():
        print(f"BACKUP AGE: 未找到 {path}（从未记录成功备份）")
        return 2
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        epoch = float(data["epoch"])
        verified = bool(data.get("verified", False))
    except Exception as e:  # noqa: BLE001 - 坏记录=失败，不能当"未知"
        print(f"BACKUP AGE: 记录不可解析：{e}")
        return 1

    age_h = (time.time() - epoch) / 3600.0
    print(f"BACKUP AGE: {age_h:.1f}h（上限 {MAX_AGE_HOURS:.0f}h） verified={verified}")
    if not verified:
        print("BACKUP AGE FAIL: 最近一次备份未通过 unzip -t / sha256 校验")
        return 1
    if age_h > MAX_AGE_HOURS:
        print("BACKUP AGE FAIL: 备份已过期——先查计划任务是否还在跑")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
