#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""数据集脱敏审计 + 指纹清单（公开数据集的前置门禁，roadmap 近期 #3）。

数据集 = offline/corpus/{chunks.jsonl,runbooks/*.md} + offline/eval/{golden_dataset.jsonl,refuse_set.jsonl}。
全部由本项目自造（合成语料），但"我们认为是合成的"不等于"可公开"——本脚本把这句话变成机器判据：
扫描真实敏感模式（邮箱/私钥块/口令赋值/公网 IP/长十六进制与 JWT 形态/手机号），命中即 FAIL，
并产出 dataset_manifest.json（逐文件 sha256 + 行数/条数）——数据集从此有可引用、可验篡改的身份。

为什么审计与清单同脚本：清单是"这份基准数据是谁"的身份证明，审计是"它能不能见人"的体检；
分两个脚本必然有人只跑一半。

用法（cwd=offline）:
  python eval/dataset_audit.py            # 审计 + 清单，报告写 eval/reports/dataset_audit.json
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

EVAL = Path(__file__).resolve().parent
OFFLINE = EVAL.parent
REPORTS = EVAL / "reports"

# 数据集清单：路径 -> (是否逐行 JSONL)
DATASET_FILES = {
    "offline/corpus/chunks.jsonl": True,
    "offline/eval/golden_dataset.jsonl": True,
    "offline/eval/refuse_set.jsonl": True,
}

RFC1918_172 = re.compile(r"172\.(1[6-9]|2\d|3[01])\.")   # 172.16-31（前缀粗判会把 172.32-39 误放行）
DOC_RANGES = ("192.0.2.", "198.51.100.", "203.0.113.")   # TEST-NET 文档段


def _is_private_or_doc_ip(ip: str) -> bool:
    if ip.startswith(("10.", "192.168.", "127.")) or RFC1918_172.match(ip):
        return True
    return ip.startswith(DOC_RANGES)


PATTERNS = {
    "email": re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
    "private_key_block": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "credential_assignment": re.compile(
        r"(?i)(password|passwd|secret|api[_-]?key|token)\s*[:=]\s*['\"]?[^'\"]{6,}"),
    "public_ip": re.compile(
        r"\b(?:(?!10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.|127\.|0\.)"
        r"(?:\d{1,3}\.){3}\d{1,3})\b"),
    "long_hex_or_jwt": re.compile(r"\b(?:[a-fA-F0-9]{40,}|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,})\b"),
    "phone_cn": re.compile(r"\b1[3-9]\d{9}\b"),
}


def audit_text(rel: str, text: str) -> list[dict]:
    findings: list[dict] = []
    for lineno, line in enumerate(text.splitlines(), 1):
        for kind, pat in PATTERNS.items():
            for m in pat.finditer(line):
                piece = m.group(0)
                if kind == "email" and piece.lower().endswith(
                        ("@example.com", "@example.org", "@example.net")):
                    continue
                if kind == "public_ip":
                    ip = piece
                    if _is_private_or_doc_ip(ip):
                        continue
                    findings.append({"file": rel, "line": lineno, "kind": kind,
                                     "sample": ip, "context": line.strip()[:160]})
                    continue
                if kind == "credential_assignment":
                    # 字段名/占位符/环境变量引用不算泄漏：password=<空>、"password": "string"、
                    # api-key: $QDRANT_API_KEY（env 引用是部署契约，不是凭据）
                    value = re.split(r"[:=]", piece, 1)[1].strip(" '\"")
                    if (not value or value.startswith(("$", "${", "<", "%"))
                            or value.lower() in ("string", "****", "***", "xxx",
                                                 "your-password", "<password>", "changeme")):
                        continue
                    findings.append({"file": rel, "line": lineno, "kind": kind,
                                     "sample": piece[:80], "context": line.strip()[:160]})
                    continue
                findings.append({"file": rel, "line": lineno, "kind": kind,
                                 "sample": piece[:80], "context": line.strip()[:160]})
    return findings


def build_manifest(repo: Path) -> dict:
    files = {}
    for rel, is_jsonl in DATASET_FILES.items():
        p = repo / rel
        h = hashlib.sha256()
        records = 0
        with open(p, "rb") as fh:
            for chunk in iter(lambda: fh.read(65536), b""):
                h.update(chunk)
        if is_jsonl:
            with open(p, encoding="utf-8") as fh:
                records = sum(1 for line in fh if line.strip())
        runbooks = sorted((repo / "offline/corpus/runbooks").glob("*.md")) if rel == "offline/corpus/chunks.jsonl" else []
        files[rel] = {"sha256": h.hexdigest(), "records": records,
                      "bytes": (repo / rel).stat().st_size}
    rb_manifest = {}
    for rb in sorted((repo / "offline/corpus/runbooks").glob("*.md")):
        h = hashlib.sha256(rb.read_bytes()).hexdigest()
        rb_manifest[rb.name] = {"sha256": h, "bytes": rb.stat().st_size}
    return {"generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "license": "CC BY 4.0（数据集）；代码许可见根 LICENSE",
            "files": files, "runbooks": rb_manifest}


def main() -> int:
    repo = OFFLINE.parent
    findings: list[dict] = []
    scanned = 0
    for rel in list(DATASET_FILES) + [
            str(p.relative_to(repo)) for p in sorted((repo / "offline/corpus/runbooks").glob("*.md"))]:
        text = (repo / rel).read_text(encoding="utf-8")
        scanned += 1
        findings.extend(audit_text(rel, text))

    manifest = build_manifest(repo)
    report = {"generated_at": manifest["generated_at"],
              "files_scanned": scanned,
              "findings": findings,
              "pass": not findings,
              "manifest": manifest}
    REPORTS.mkdir(parents=True, exist_ok=True)
    out = REPORTS / "dataset_audit.json"
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    manifest_path = repo / "offline" / "eval" / "dataset_manifest.json"
    with open(manifest_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)

    if findings:
        for f in findings[:20]:
            print(f"SUSPECT {f['kind']} {f['file']}:{f['line']} -> {f['sample']}")
        print(f"FAIL 数据集脱敏审计：{len(findings)} 处命中（报告 {out}）", file=sys.stderr)
        return 1
    print(f"PASS 数据集脱敏审计：{scanned} 个文件 0 命中；清单 {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
