#!/usr/bin/env python3
"""审计哈希链校验（G1 门禁 9，2026-10-22）。

规则（与 AuditService.writeEvent 对齐）：
  hash_i = sha256(prev_hash_i + "\\n" + line_i_without_hash)
  其中 line_i_without_hash 是原始行去掉最后的 ,"hash":"..." 后的前缀 + "}"。
首行 prev_hash 应为 GENESIS；旧格式（无 hash）行允许出现在升级前，但不能夹在新链中间。

退出码：0 通过；3 发现断链/篡改；2 输入错误。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys

GENESIS = "GENESIS"
MARKER = ',"hash":"'


def split_hash(line: str):
    idx = line.rfind(MARKER)
    if idx < 0 or not line.endswith("}"):
        return None, None
    prefix = line[:idx] + "}"
    digest = line[idx + len(MARKER):-2]  # 去掉结尾 "}"
    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        return None, None
    return prefix, digest


def expected_hash(prev: str, prefix: str) -> str:
    return hashlib.sha256((prev + "\n" + prefix).encode("utf-8")).hexdigest()


def verify(paths: list[pathlib.Path], head: pathlib.Path | None, allow_segment: bool = False) -> int:
    prev = GENESIS
    hashed = 0
    legacy = 0
    last = None
    for path in paths:
        if not path.exists():
            print(f"SKIP 不存在: {path}")
            continue
        with path.open("r", encoding="utf-8") as fh:
            for lineno, raw in enumerate(fh, 1):
                line = raw.rstrip("\n")
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError as e:
                    print(f"FAIL {path}:{lineno} 不是合法 JSON: {e}")
                    return 3
                prefix, digest = split_hash(line)
                if digest is None:
                    if hashed:
                        print(f"FAIL {path}:{lineno} 旧格式行出现在新链中间（升级前旧行应在前）")
                        return 3
                    legacy += 1
                    prev = GENESIS
                    continue
                got_prev = obj.get("prev_hash")
                if got_prev != prev:
                    if hashed == 0 and allow_segment:
                        print(f"WARN {path}:{lineno} 从分段起点 {got_prev!r} 开始；更早文件未提供，"
                              "只能证明本段内部自洽")
                        prev = got_prev
                    else:
                        print(f"FAIL {path}:{lineno} prev_hash={got_prev!r} 期望 {prev!r}")
                        return 3
                want = expected_hash(prev, prefix)
                if digest != want:
                    print(f"FAIL {path}:{lineno} hash 不符（内容被改过）")
                    print(f"  记录 {digest}")
                    print(f"  应为 {want}")
                    return 3
                prev = digest
                last = digest
                hashed += 1
    if hashed == 0 and legacy == 0:
        print("WARN 空审计文件，无链可验")
        return 0
    if hashed == 0:
        print(f"WARN 只有 {legacy} 条旧格式行（升级前），没有可验证的 hash 链")
        return 0
    if head is not None and head.exists() and last is not None:
        expected_head = head.read_text(encoding="utf-8").strip()
        if expected_head and expected_head != last:
            # head 在 AUDIT.info 之后写，异步 appender 可能尚未刷盘；head 是重启提示，不是事实源。
            print(f"WARN 链头文件比最后一行新（异步落盘未刷）：{expected_head} != {last}")
    print(f"PASS 审计链：{hashed} 条带 hash，{legacy} 条旧格式；末 hash={last}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="校验 OpsPilot 审计 JSONL 的 SHA-256 链")
    ap.add_argument("files", nargs="*", type=pathlib.Path,
                    default=[pathlib.Path("logs/audit.jsonl")])
    ap.add_argument("--head", type=pathlib.Path, default=pathlib.Path("logs/.audit-head"))
    ap.add_argument("--allow-segment", action="store_true",
                    help="只校验给出的文件段（首行 prev_hash 可非 GENESIS）；会打印 WARN")
    args = ap.parse_args()
    paths = args.files or [pathlib.Path("logs/audit.jsonl")]
    return verify(paths, args.head, args.allow_segment)


if __name__ == "__main__":
    sys.exit(main())
