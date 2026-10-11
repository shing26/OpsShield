# -*- coding: utf-8 -*-
"""外部存活探测的回归锁（`scripts/liveness.py`）。

锁四件事，全部不触网（假 opener 注入）：

  1. **身份先于存活**——`WRONG_SERVICE` 必须能被单独判出来。理由是本机实测：
     8091 被另一个项目的 biz-mock 占着，而**任何 Spring Boot 的
     `/actuator/health` 都返回 `{"status":"UP"}``**。只探 health 的探针会给
     别人的进程做体检，"UP 了所以我活着"是最危险的假绿。两个信号缺一不可。
  2. **阈值去抖**：单次失败不判死（网络抖一下就报警的探针，人会学会忽略它）。
  3. **恢复即清零**：一次抖动不许累积成永久告警（理由同上）。
  4. **环回白名单**：只该探本机。外网地址必须在发请求前就被拒——否则这个
     脚本自己就成了 SSRF 面（与 `localapi.assert_local` 同一口径）。
"""
from __future__ import annotations

import io
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts"))

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把不变量改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
import liveness as lv  # noqa: E402

OK_HTML = "<html><title>OpsShield · Ops Console</title></html>"


class FakeResponse(io.BytesIO):
    def __init__(self, code: int, body: str):
        super().__init__(body.encode("utf-8"))
        self.status = code

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def opener_for(routes: dict[str, tuple[int, str]], calls: list | None = None):
    """按 URL 尾段返回预设响应；未登记的 URL 直接炸（防止脚本偷探别处）。"""

    def _open(url, timeout=None):
        if calls is not None:
            calls.append(url)
        for frag, (code, body) in routes.items():
            if url.endswith(frag):
                return FakeResponse(code, body)
        raise AssertionError(f"探测了未登记的 URL: {url}")

    return _open


# ---------- 1. 身份先于存活 ----------

def test_healthy_requires_both_identity_and_up():
    op = opener_for({
        "/": (200, OK_HTML),
        "/actuator/health": (200, json.dumps({"status": "UP"})),
    })
    assert lv.probe("http://localhost:8081", opener=op) == (lv.HEALTHY, "UP")


def test_wrong_service_is_distinguished_from_down():
    """别人的进程回 UP —— 必须判成 WRONG 而不是 HEALTHY。"""
    op = opener_for({
        "/": (200, "<html><title>Some Other App</title></html>"),
        "/actuator/health": (200, json.dumps({"status": "UP"})),
    })
    state, detail = lv.probe("http://localhost:8081", opener=op)
    assert state == lv.WRONG
    assert "not OpsShield" in detail or "不是 OpsShield" in detail


def test_unreachable_when_root_refuses_connection():
    def _boom(url, timeout=None):
        raise urllib.error.URLError("Connection refused")

    state, _ = lv.probe("http://localhost:8081", opener=_boom)
    assert state == lv.UNREACHABLE


def test_health_non_up_is_down_not_healthy():
    """身份对但 health 非 UP → DOWN（处置动作是查中间件，不是查端口）。"""
    op = opener_for({
        "/": (200, OK_HTML),
        "/actuator/health": (503, json.dumps({"status": "DOWN"})),
    })
    state, detail = lv.probe("http://localhost:8081", opener=op)
    assert state == lv.DOWN
    assert "DOWN" in detail


def test_health_unparseable_body_is_down_not_healthy():
    """body 不是 JSON 时不许乐观当健康——那是今天实测过的假绿路径。"""
    op = opener_for({"/": (200, OK_HTML), "/actuator/health": (200, "<html>whoops</html>")})
    assert lv.probe("http://localhost:8081", opener=op)[0] == lv.DOWN


def test_probe_only_touches_root_and_health():
    """只探两个端点：多探一个就多一份权限面与一份被打扰的余地。"""
    calls: list[str] = []
    op = opener_for({
        "/": (200, OK_HTML),
        "/actuator/health": (200, json.dumps({"status": "UP"})),
    }, calls)
    lv.probe("http://localhost:8081", opener=op)
    assert calls == ["http://localhost:8081/", "http://localhost:8081/actuator/health"]


# ---------- 2/3. 阈值去抖 + 恢复清零 ----------

def test_single_failure_does_not_trip_threshold():
    w = lv.Watch(threshold=2)
    assert w.record(lv.DOWN) is False
    assert w.record(lv.DOWN) is True


def test_health_resets_consecutive_failures():
    """抖一下→健康→再抖一下：不该被判死（不清零就会永久告警）。"""
    w = lv.Watch(threshold=2)
    w.record(lv.DOWN)
    w.record(lv.HEALTHY)
    assert w.consecutive == 0
    assert w.record(lv.DOWN) is False


def test_threshold_one_trips_immediately():
    w = lv.Watch(threshold=1)
    assert w.record(lv.UNREACHABLE) is True


# ---------- 4. 环回白名单 ----------

@pytest.mark.parametrize("bad", [
    "http://evil.example.com:8081",
    "https://localhost:8081",                       # 非明文 http
    "http://user:pw@localhost:8081",                # userinfo 混淆
    "http://192.168.1.10:8081",                     # 非环回
])
def test_non_loopback_base_is_refused_without_probing(bad, monkeypatch, capsys):
    def _explode(*a, **k):
        raise AssertionError("不该发请求")

    monkeypatch.setattr(lv.urllib.request, "urlopen", _explode)
    assert lv.main(["--once", "--base", bad, "--log", str(REPO / "logs" / "_t.jsonl")]) == 2
    assert "环回" in capsys.readouterr().out


def test_loopback_base_accepted(monkeypatch, tmp_path):
    """正向：环回地址放行且真的探了（变异验证的反向——正常流程不得被门闩挡住）。"""
    monkeypatch.setattr(lv, "probe", lambda *a, **k: (lv.HEALTHY, "UP"))
    assert lv.main(["--once", "--base", "http://127.0.0.1:8081",
                    "--log", str(tmp_path / "l.jsonl")]) == 0


# ---------- 落盘格式 ----------

def test_event_log_is_jsonl_with_state_and_base(tmp_path):
    p = tmp_path / "liveness.jsonl"
    lv.append_log(p, lv.WRONG, "端口被占", "http://localhost:8081")
    rec = json.loads(p.read_text(encoding="utf-8").strip())
    assert rec["state"] == lv.WRONG and rec["base"] == "http://localhost:8081"
    assert isinstance(rec["ts"], int)


def test_once_mode_exit_code_reflects_state(monkeypatch, tmp_path):
    """--once 的退出码是给计划任务看的，必须能区分健康/异常。"""
    log = str(tmp_path / "l.jsonl")
    monkeypatch.setattr(lv, "probe", lambda *a, **k: (lv.HEALTHY, "UP"))
    assert lv.main(["--once", "--log", log]) == 0
    monkeypatch.setattr(lv, "probe", lambda *a, **k: (lv.DOWN, "health status=DOWN http=503"))
    assert lv.main(["--once", "--log", log]) == 1