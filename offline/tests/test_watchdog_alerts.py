# -*- coding: utf-8 -*-
"""看门狗告警源的防御性回归锁（`scripts/watchdog_alerts.py`）。

锁两条 2026-10-22 G0 修掉的**静默失效**：

  1. **login 在 try 内**——口令错 / 主体被锁 / 登录面不可达时，send_alert 必须返回失败结果，
     不能把异常抛成常驻进程的退出路径。此前 login 在 try 外：看门狗会因为自己登不上而
     全停，且停摆本身没有任何告警（最深的静默失效就在这里）。
  2. **退避有封顶**——连续失败按指数退避、封顶 5 分钟；漏了封顶就会在中间件抖动期
     变成 1s 一次的忙轮询，而那种现场事后很难复现，故判据必须可测。

不触网：localapi 的 login / stream_chat 全部被替换。
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "offline"))   # localapi 的事实源目录（watchdog 自己也会插）
sys.path.insert(0, str(REPO / "scripts"))

import localapi  # noqa: E402
import watchdog_alerts as wd  # noqa: E402


def test_send_alert_returns_failure_when_login_raises(monkeypatch):
    """登录失败是**数据**（返回 status=-1），不是异常——否则常驻进程整条退出。"""
    def _boom(user):
        raise RuntimeError("locked")

    monkeypatch.setattr(localapi, "login", _boom)
    monkeypatch.setattr(localapi, "stream_chat",
                        lambda *a, **k: pytest.fail("login 失败后不该继续调用编排面"))
    out = wd.send_alert("容器 x 已退出", "local", 5)
    assert out["status"] == -1 and "locked" in out["error"]


def test_send_alert_returns_failure_when_stream_chat_raises(monkeypatch):
    monkeypatch.setattr(localapi, "login", lambda user: "t")
    monkeypatch.setattr(localapi, "stream_chat",
                        lambda *a, **k: (_ for _ in ()).throw(ConnectionRefusedError("refused")))
    out = wd.send_alert("容器 x 已退出", "local", 5)
    assert out["status"] == -1 and "refused" in out["error"]


def test_send_alert_returns_meta_on_success(monkeypatch):
    monkeypatch.setattr(localapi, "login", lambda user: "t")
    monkeypatch.setattr(localapi, "stream_chat", lambda *a, **k: {
        "status": 200, "meta": {"fingerprint": "abc"}, "done": {}})
    out = wd.send_alert("容器 x 已退出", "local", 5)
    assert out["status"] == 200 and out["meta"]["fingerprint"] == "abc"


def test_backoff_grows_then_caps():
    assert wd.backoff_delay(10, 0) == 10, "0 次失败不该被当成 1 次"
    assert wd.backoff_delay(10, 1) == 10
    assert wd.backoff_delay(10, 2) == 20
    assert wd.backoff_delay(10, 3) == 40
    assert wd.backoff_delay(10, 100) == wd.CRASH_BACKOFF_MAX == 300, "必须封顶，否则抖动期忙轮询"
