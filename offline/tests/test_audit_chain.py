"""审计哈希链校验器回归（G1 门禁 9）：有效链通过，改一行必须失败。"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("verify_audit_chain", ROOT / "scripts" / "verify_audit_chain.py")
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)


def _line(prev: str, event: dict) -> tuple[str, str]:
    event = dict(event)
    event["prev_hash"] = prev
    prefix = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
    digest = hashlib.sha256((prev + "\n" + prefix).encode("utf-8")).hexdigest()
    return prefix[:-1] + ',"hash":"' + digest + '"}', digest


def _write_chain(tmp_path: pathlib.Path, events: list[dict]):
    prev = "GENESIS"
    lines = []
    for ev in events:
        line, prev = _line(prev, ev)
        lines.append(line)
    audit = tmp_path / "audit.jsonl"
    audit.write_text("\n".join(lines) + "\n", encoding="utf-8")
    (tmp_path / ".audit-head").write_text(prev, encoding="utf-8")
    return audit, tmp_path / ".audit-head"


def test_valid_chain_passes(tmp_path):
    audit, head = _write_chain(tmp_path, [{"seq": 1, "ev": "chat"}, {"seq": 2, "ev": "chat"}])
    assert _mod.verify([audit], head) == 0


def test_tampered_line_fails(tmp_path):
    audit, head = _write_chain(tmp_path, [{"seq": 1, "ev": "chat"}, {"seq": 2, "ev": "chat"}])
    text = audit.read_text(encoding="utf-8").replace('"ev":"chat"', '"ev":"admin"', 1)
    audit.write_text(text, encoding="utf-8")
    assert _mod.verify([audit], head) == 3
