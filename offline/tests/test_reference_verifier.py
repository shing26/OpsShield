"""L1 reference verifier 的单测：四个状态码 + 校验顺序 + 正路径。

spec §6：签名(401) -> 内容哈希(422) -> 过期(410) -> 幂等(409)，
顺序本身是判据——"签名错但已过期"必须报 401 而不是 410。"""
from __future__ import annotations

import importlib
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "offline" / "redteam" / "verify"))
rv = importlib.import_module("reference_verifier")

KEY = bytes.fromhex("bd" * 32)
BAD_KEY = bytes.fromhex("aa" * 32)
OP = "act_test"
PAYLOAD = b"kubectl rollout restart deployment/order-api -n prod"


def _sig(sha, exp, key=KEY):
    return rv.expected_signature(key, OP, sha, exp)


def _run(sha, sig, exp, state_file, now=None):
    return rv.verify(OP, PAYLOAD, sha, sig, exp, KEY, state_file, now=now)


def test_good_path_200_then_replay_409(tmp_path):
    sha = rv.payload_sha256(PAYLOAD)
    exp = (datetime.now(timezone.utc) + timedelta(minutes=15)).isoformat()
    state = tmp_path / "state.json"
    assert _run(sha, _sig(sha, exp), exp, state)[0] == 200
    code, _ = _run(sha, _sig(sha, exp), exp, state)
    assert code == 409


def test_wrong_signature_401_even_if_also_expired(tmp_path):
    """顺序判据：签名错 + 已过期 → 401（第一层先拦，不漏报到第二层）。"""
    sha = rv.payload_sha256(PAYLOAD)
    past = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
    code, _ = _run(sha, _sig(sha, past, BAD_KEY), past, tmp_path / "s.json")
    assert code == 401


def test_tampered_payload_422_beats_expiry(tmp_path):
    """顺序判据：签名对（对原 sha 签的）+ 内容被改 + 已过期 → 422（先于 410）。"""
    sha = rv.payload_sha256(PAYLOAD)
    past = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
    tampered = PAYLOAD + b" # tampered"
    code, _ = _run(sha, _sig(sha, past), past, tmp_path / "s.json",
                   ) if False else rv.verify(
        OP, tampered, sha, _sig(sha, past), past, KEY, tmp_path / "s.json")
    assert code == 422


def test_expired_410(tmp_path):
    sha = rv.payload_sha256(PAYLOAD)
    past = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
    code, _ = _run(sha, _sig(sha, past), past, tmp_path / "s.json")
    assert code == 410