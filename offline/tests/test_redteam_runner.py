"""红队 runner 的纯逻辑单测：manifest 双向对账（防删/防篡改/防塞）。

不碰网络/网关——真端到端由 scripts/redteam.sh 对活体栈负责（MCP 用例必须真 stdio，
这里不替代）。"""
from __future__ import annotations

import importlib
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "offline" / "redteam"))
runner = importlib.import_module("runner")


def _make_cases(tmp_path, contents: dict):
    cases = tmp_path / "cases"
    cases.mkdir()
    for name, body in contents.items():
        (cases / name).write_text(body, encoding="utf-8")


def _patch(tmp_path, monkeypatch):
    monkeypatch.setattr(runner, "REDTEAM", tmp_path)     # relative_to 的根也要换
    monkeypatch.setattr(runner, "CASES", tmp_path / "cases")
    monkeypatch.setattr(runner, "MANIFEST", tmp_path / "manifest.json")


def test_manifest_roundtrip_ok(tmp_path, monkeypatch):
    _make_cases(tmp_path, {"a.json": '{"id":"a"}', "b.json": '{"id":"b"}'})
    _patch(tmp_path, monkeypatch)
    runner.update_manifest()
    runner.verify_manifest()                           # 基线：一致


def test_manifest_detects_case_tamper(tmp_path, monkeypatch):
    _make_cases(tmp_path, {"a.json": '{"id":"a"}'})
    _patch(tmp_path, monkeypatch)
    runner.update_manifest()
    (tmp_path / "cases" / "a.json").write_text('{"id":"a","attack":"changed"}',
                                               encoding="utf-8")
    with pytest.raises(SystemExit):                    # 篡改 = 红
        runner.verify_manifest()


def test_manifest_detects_deleted_hard_case(tmp_path, monkeypatch):
    _make_cases(tmp_path, {"a.json": '{"id":"a"}', "hard.json": '{"id":"hard"}'})
    _patch(tmp_path, monkeypatch)
    runner.update_manifest()
    (tmp_path / "cases" / "hard.json").unlink()
    with pytest.raises(SystemExit):                    # 删难过的用例 = 红
        runner.verify_manifest()


def test_manifest_detects_unregistered_case(tmp_path, monkeypatch):
    _make_cases(tmp_path, {"a.json": '{"id":"a"}'})
    _patch(tmp_path, monkeypatch)
    runner.update_manifest()
    (tmp_path / "cases" / "sneaky.json").write_text('{"id":"sneaky"}',
                                                    encoding="utf-8")
    with pytest.raises(SystemExit):                    # 塞清单外用例刷分 = 红
        runner.verify_manifest()


def test_verdict_normalization_is_fail_closed():
    """checker 输出无 pass/SKIP 前缀时必须记 LEAK——判定器坏了不能算通过。"""
    src = Path(runner.__file__).read_text(encoding="utf-8")
    assert "判定器输出无前缀（fail-closed）" in src
    assert "check_same_tenant_only" in src             # 围栏判据：结果都属于我
    assert "CHAT_CHECKS" in src                        # chat 面单独注册