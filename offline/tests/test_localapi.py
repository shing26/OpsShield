# -*- coding: utf-8 -*-
r"""`offline/localapi.py` 的回归锁：基址覆盖口 + 默认主体常量。

锁这两件的理由，都来自 2026-09-24 那一次"live 报告跑不出来"的实际卡点：

  1. **基址覆盖口**：`localapi.BASE` 曾硬编码 8081，而 `console_client` 早有 `OPSPILOT_BASE`
     ——两者不对称，使"本机 8081 恰好被别的进程占用"直接等于"整条 live 验证链不可做"
     （acceptance_a2/a3、evaluate、四个测量脚本、alert_producer 全部改不了目标端口）。
     覆盖口**只改默认值**，`assert_local` 仍是唯一执行点——故必须同时锁"改得动"与"防线没被削弱"。
  2. **默认主体**：`ACCOUNTS` 的**键**（`sre_l3`）是内部别名、不是真实用户名。曾有脚本把键当
     用户名传进 `login()`——跑起来必然 401，还会触发登录失败锁定。锁法两层：常量必须是
     `ACCOUNTS` 的**值**，且必须是 `seed_demo_users.sh` 真正播种的用户名（现算那个脚本，不靠记性）；
     另加 AST 检查，断言四个测量脚本的 `--user` 默认值都**引用该常量**而非硬编码字面量。
"""
from __future__ import annotations

import ast
import re
import sys
from pathlib import Path

import pytest

OFFLINE = Path(__file__).resolve().parent.parent
REPO = OFFLINE.parent

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把不变量改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
sys.path.insert(0, str(OFFLINE))
import localapi as api  # noqa: E402


# ---------------------------------------------------------------- 基址覆盖口

def test_base_defaults_to_8081_when_env_absent():
    assert api._resolve_base({}) == "http://localhost:8081"


def test_base_honors_env_override_and_strips_trailing_slash():
    assert api._resolve_base({"OPSPILOT_BASE": "http://localhost:8099"}) == "http://localhost:8099"
    assert api._resolve_base({"OPSPILOT_BASE": "http://127.0.0.1:8099/"}) == "http://127.0.0.1:8099"


def test_override_does_not_weaken_the_ssrf_guard():
    """覆盖口只改默认值——把 base 指到非环回地址一样要被拒。

    三条拒绝都发生在 `getaddrinfo` **之前**（主机白名单 / scheme / userinfo 三处前置检查），
    故本用例不触网——这是刻意的：一旦写成"解析后才拒"的形式，用例就会引入 DNS 依赖。
    """
    for bad in (
        "http://evil.com:8099/x",               # 非白名单主机
        "https://localhost:8099/x",             # 非 http（https 不走本白名单口径）
        "http://user:pw@localhost:8099/x",      # userinfo 混淆手法
    ):
        with pytest.raises(ValueError):
            api.assert_local(bad)


# ---------------------------------------------------------------- trace 入参

def _captured_stream_request(monkeypatch, **kw):
    """跑一次 stream_chat，把真正发出去的 Request 钓出来（不触网：`_open` 被替换）。"""
    seen = {}

    class _Resp:
        status = 200

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def __iter__(self):
            return iter(())

    def _fake(req, timeout):
        seen["req"] = req
        return _Resp()

    monkeypatch.setattr(api, "_open", _fake)
    api.stream_chat({"query": "q"}, "tok", **kw)
    return seen["req"]


def _header(req, name: str) -> str | None:
    """大小写无关地取请求头。

    `urllib.request.Request.add_header` 会把键 `capitalize()` 成 `X-trace-id`，
    故直接 `get_header("X-Trace-Id")` 会取不到——用键名大小写定义断言是在测 urllib 的实现细节。
    """
    return {k.lower(): v for k, v in req.headers.items()}.get(name.lower())


def test_stream_chat_sends_trace_header_only_when_given(monkeypatch):
    r"""`X-Trace-Id` 入参的三态：给了就带、不给不带、空串等同不给。

    第三态是重点：服务端对**空白头**按"未提供"处理，所以多带一个空头不会 400——
    但它会污染请求面，且"头在但值空"这类形态在抓包/日志里最难判读。宁可不送。
    """
    iid = "am-" + "0" * 32
    assert _header(_captured_stream_request(monkeypatch, trace_id=iid), "X-Trace-Id") == iid
    assert _header(_captured_stream_request(monkeypatch), "X-Trace-Id") is None
    assert _header(_captured_stream_request(monkeypatch, trace_id=""), "X-Trace-Id") is None


# ---------------------------------------------------------------- 默认主体常量

def test_default_eval_user_is_a_real_username_not_an_account_key():
    assert api.DEFAULT_EVAL_USER in api.ACCOUNTS.values(), \
        "默认主体必须是真实用户名（ACCOUNTS 的值）"
    assert api.DEFAULT_EVAL_USER not in api.ACCOUNTS, \
        "ACCOUNTS 的键是内部别名（sre_l3），不是可登录的用户名"


def _seeded_usernames() -> set[str]:
    """现算 `seed_demo_users.sh` 真正播种的用户名（行格式：`add <user> <tenant> <level> <role>`）。"""
    src = (REPO / "scripts" / "seed_demo_users.sh").read_text(encoding="utf-8")
    return set(re.findall(r"^\s*add\s+(\S+)", src, re.M))


def test_default_eval_user_is_actually_seeded():
    """结构性锁：默认主体必须真的在播种集合里，否则 live 跑必然 401（而且会锁住账号）。"""
    seeded = _seeded_usernames()
    assert seeded, "没能从 seed_demo_users.sh 解析出用户名——脚本格式变了？"
    assert api.DEFAULT_EVAL_USER in seeded, \
        f"默认主体 {api.DEFAULT_EVAL_USER} 不在播种集合里；播种的有 {sorted(seeded)}"


MEASUREMENT_SCRIPTS = ("eval/gate_matrix.py", "eval/observed_probe.py",
                       "load/sweep.py", "load/cache_savings.py")


def test_measurement_scripts_reference_the_constant_not_a_literal():
    """AST 锁：四个测量脚本的 `--user` 默认值必须**引用** `localapi.DEFAULT_EVAL_USER`。

    为什么用 AST 而不是源码文本：文本匹配会被 docstring/注释里的同类字符串打成假红或假绿
    （本轮已踩过一次）。AST 只认真正的 `default=` 关键字实参。

    变异验证（改坏必红）：把任一处改回 `default="sre-l3"` 或别的字面量 → 本用例必红。
    """
    for rel in MEASUREMENT_SCRIPTS:
        path = OFFLINE / rel
        tree = ast.parse(path.read_text(encoding="utf-8"))
        found = None
        for node in ast.walk(tree):
            if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and node.func.attr == "add_argument"):
                continue
            if not (node.args and isinstance(node.args[0], ast.Constant)
                    and node.args[0].value == "--user"):
                continue
            for kw in node.keywords:
                if kw.arg == "default":
                    found = kw.value
        assert found is not None, f"{rel} 里没找到 --user 的 default"
        assert isinstance(found, ast.Attribute) and found.attr == "DEFAULT_EVAL_USER", \
            f"{rel} 的 --user 默认值必须引用 localapi.DEFAULT_EVAL_USER（当前 AST: {ast.dump(found)}）"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
