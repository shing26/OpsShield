"""L1 WORM 门禁单测：每条 L1 事件必须有不可变副本（盲点 6）。"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("l1_worm_gate", REPO / "scripts" / "l1_worm_gate.py")
gw = importlib.util.module_from_spec(spec)
sys.modules["l1_worm_gate"] = gw
spec.loader.exec_module(gw)


def _ev(ev, ts=1, sub="sre-full", q="op"):
    return {"ts": ts, "ev": ev, "sub": sub, "q": q}


def test_all_l1_events_covered_passes(tmp_path):
    audit = tmp_path / "audit.jsonl"
    worm = tmp_path / "worm"
    worm.mkdir()
    events = [_ev("action_proposed", 1), _ev("action_confirmed", 2),
              _ev("action_dispatched", 3), _ev("action_result", 4)]
    audit.write_text("\n".join(json.dumps(e) for e in events), encoding="utf-8")
    (worm / "audit-2026-10-26.jsonl").write_text(
        "\n".join(json.dumps(e) for e in events), encoding="utf-8")
    assert gw.check(audit, worm) == (0, [])


def test_missing_l1_event_fails_closed(tmp_path):
    audit = tmp_path / "audit.jsonl"
    worm = tmp_path / "worm"
    worm.mkdir()
    events = [_ev("action_proposed", 1), _ev("action_confirmed", 2)]
    audit.write_text("\n".join(json.dumps(e) for e in events), encoding="utf-8")
    # WORM 只落了 proposed——confirmed 缺失即 FAIL（不许"大概写进去了"）
    (worm / "a.jsonl").write_text(json.dumps(events[0]), encoding="utf-8")
    code, problems = gw.check(audit, worm)
    assert code == 1
    assert any("action_confirmed" in p for p in problems)


def test_non_l1_events_are_not_gated(tmp_path):
    audit = tmp_path / "audit.jsonl"
    worm = tmp_path / "worm"
    worm.mkdir()
    audit.write_text("\n".join(json.dumps(e) for e in [
        _ev("chat_ok", 1), _ev("search_ok", 2), _ev("action_proposed", 3)]),
        encoding="utf-8")
    (worm / "a.jsonl").write_text(json.dumps(_ev("action_proposed", 3)), encoding="utf-8")
    assert gw.check(audit, worm) == (0, [])


def test_missing_worm_dir_is_config_error(tmp_path):
    import subprocess
    r = subprocess.run([sys.executable, str(REPO / "scripts" / "l1_worm_gate.py"),
                        "--audit", str(tmp_path / "none.jsonl"),
                        "--worm-dir", str(tmp_path / "nope")],
                       capture_output=True, text=True)
    assert r.returncode == 2