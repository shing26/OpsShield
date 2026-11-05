"""门禁 6/7 报表生成器的不变式：读不到就写"无数据"、证明不了就写 UNPROVEN、判定不掺水。

为什么这些测试必须存在：
- 这份报表最终会被抄进门禁复核记录。它一旦把"没抓到"渲染成 0、把旧格式样本当成通过，
  门禁就会被一份看起来很专业的表格骗过——那比没有报表更糟。
- 连续性证据是按周固化的，窗口/UTC/文件名必须可复现，否则第 30 天对不上账。
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
from datetime import date
from pathlib import Path

OFFLINE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(OFFLINE))

import slo_report as sr  # noqa: E402


class FakeProm:
    def __init__(self, retention=30.0, values=None, fail=False):
        self.retention = retention
        self.values = values or {}
        self.fail = fail

    def retention_days(self):
        if self.fail:
            raise sr.PrometheusError("boom")
        return self.retention

    def query(self, expr, at):
        if self.fail:
            raise sr.PrometheusError("boom")
        for key, value in self.values.items():
            if key in expr:
                return value
        return None


def sample(**over) -> dict:
    row = {
        "schema": 2, "concurrency": 20, "started_at": "2026-11-02T10:00:00+0800",
        "load": {"total": 80, "ok": 4, "rejected_429": 76, "other": 0,
                 "retry_after_ok": 76, "retry_after_missing": 0},
        "control": {"total": 5, "ok": 5, "rejected_429": 0, "other": 0},
        "degradation": {"state": 0.0, "level": "L0"},
    }
    row.update(over)
    return row


def write_sample(tmp: Path, name: str, data: dict) -> Path:
    p = tmp / name
    p.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return p


# ---- 保留期与窗口 ---------------------------------------------------------------

def test_parse_retention_units():
    assert sr.parse_retention("30d") == 30.0
    assert sr.parse_retention("720h") == 30.0
    assert sr.parse_retention("2w") == 14.0
    assert sr.parse_retention("nonsense") is None, "解析不了就返回 None，不猜默认 15d"


def test_weekly_window_is_seven_days_inclusive():
    start, end, days = sr.window_for("weekly", date(2026, 11, 9), None)
    assert (start, end, days) == (date(2026, 11, 3), date(2026, 11, 9), 7)


def test_monthly_window_is_the_calendar_month_with_december_rollover():
    assert sr.window_for("monthly", date(2026, 11, 5), None) == (
        date(2026, 11, 1), date(2026, 11, 30), 30)
    assert sr.window_for("monthly", date(2026, 12, 31), "2026-12") == (
        date(2026, 12, 1), date(2026, 12, 31), 31)


# ---- Prometheus 读取 ------------------------------------------------------------

def test_missing_series_is_none_not_zero():
    client = sr.PromClient("http://x", opener=lambda url, timeout=10: _Resp(
        {"status": "success", "data": {"resultType": "vector", "result": []}}))
    assert client.query("up", sr._window_end(date(2026, 11, 1))) is None, \
        "没有序列 ≠ 0：报表必须显示'无数据'，否则门禁会把没接线当成零故障"


def test_error_status_raises():
    client = sr.PromClient("http://x", opener=lambda url, timeout=10: _Resp(
        {"status": "error", "error": "bad_data"}))
    try:
        client.query("up", sr._window_end(date(2026, 11, 1)))
    except sr.PrometheusError as exc:
        assert "status" in str(exc)
    else:
        raise AssertionError("Prometheus 返回 error 必须抛，不能静默当空")


def test_non_vector_result_is_rejected():
    client = sr.PromClient("http://x", opener=lambda url, timeout=10: _Resp(
        {"status": "success", "data": {"resultType": "matrix", "result": []}}))
    try:
        client.query("up", sr._window_end(date(2026, 11, 1)))
    except sr.PrometheusError:
        pass
    else:
        raise AssertionError("matrix 形状说明查询写错了，应当报错而不是猜")


def test_collect_sli_keeps_none_and_warns():
    client = FakeProm(values={"aiops_requests_total": 100.0})
    sli, warnings = sr.collect_sli(client, date(2026, 11, 3), date(2026, 11, 9), 7)
    assert sli["requests_total"] == 100.0
    assert sli["ttft_p95"] is None
    assert warnings == [], "空向量不是错误：只有查询失败才进 warnings"


def test_coverage_below_threshold_counts_as_missing():
    client = FakeProm(values={"count_over_time": 10.0})
    covered, missing, notes = sr.scrape_coverage(client, date(2026, 11, 1), date(2026, 11, 2))
    assert covered == [] and len(missing) == 2
    assert any("10 个抓取点" in n for n in notes), "要说明为什么判缺失，而不是静默"


# ---- 单样本判定 -----------------------------------------------------------------

def test_healthy_sample_passes():
    assert sr.judge_sample(sample()) == ("PASS", [])


def test_sample_without_429_is_unproven_not_pass():
    # 2026-11-05 live 实测：c4 并发数在许可之内，0×429 是预期，不是违规。
    # 判 FAIL 会让按周固定跑的低并发档永久打红门禁——那是在惩罚按文档办事的人。
    verdict, reasons = sr.judge_sample(sample(load={
        "total": 80, "ok": 80, "rejected_429": 0, "other": 0,
        "retry_after_ok": 0, "retry_after_missing": 0}, concurrency=4))
    assert verdict == "UNPROVEN" and "未触发削峰" in reasons[0]


def test_no_429_with_other_hard_violations_still_fails():
    # UNPROVEN 只兜"证据不够"，不掩盖真违规：5xx 与对照租户被连带仍判 FAIL。
    verdict, reasons = sr.judge_sample(sample(load={
        "total": 80, "ok": 78, "rejected_429": 0, "other": 2,
        "retry_after_ok": 0, "retry_after_missing": 0}, concurrency=4))
    assert verdict == "FAIL" and "非 200/429" in reasons[0]


def test_load_5xx_fails():
    verdict, reasons = sr.judge_sample(sample(load={
        "total": 80, "ok": 4, "rejected_429": 75, "other": 1,
        "retry_after_ok": 75, "retry_after_missing": 0}))
    assert verdict == "FAIL" and "非 200/429" in reasons[0]


def test_control_tenant_must_be_all_200():
    verdict, reasons = sr.judge_sample(sample(control={"total": 5, "ok": 4, "rejected_429": 1, "other": 0}))
    assert verdict == "FAIL" and "对照租户" in reasons[0]


def test_missing_retry_after_fails_schema2():
    verdict, reasons = sr.judge_sample(sample(load={
        "total": 80, "ok": 4, "rejected_429": 76, "other": 0,
        "retry_after_ok": 70, "retry_after_missing": 6}))
    assert verdict == "FAIL" and "缺 Retry-After" in reasons[0]


def test_degradation_above_l0_fails():
    verdict, reasons = sr.judge_sample(sample(degradation={"state": 1.0, "level": "L1"}))
    assert verdict == "FAIL" and "L1" in reasons[0]


def test_schema1_sample_is_unproven_not_pass():
    data = sample(schema=1)
    data.pop("degradation")
    verdict, reasons = sr.judge_sample(data)
    assert verdict == "UNPROVEN" and "schema 1" in reasons[0], \
        "旧样本缺字段：没有证据不等于合规，也不等于违规"


def test_missing_degradation_field_is_unproven():
    data = sample()
    data.pop("degradation")
    verdict, _ = sr.judge_sample(data)
    assert verdict == "UNPROVEN"


# ---- 门禁聚合 -------------------------------------------------------------------

def test_gate6_counts_two_records_per_sample():
    samples = [{"verdict": "PASS"}, {"verdict": "PASS"}, {"verdict": "UNPROVEN"}]
    g6 = sr.gate6(samples)
    assert g6["passing_records"] == 4 and g6["records"] == 6
    assert g6["verdict"] == "INCOMPLETE"


def test_gate6_green_at_twenty_four_records():
    assert sr.gate6([{"verdict": "PASS"}] * 12)["verdict"] == "GREEN"


def test_gate7_needs_both_thirty_days_and_thirty_day_retention():
    covered = [f"2026-11-{d:02d}" for d in range(1, 31)]
    assert sr.gate7(covered, [], 30.0)["verdict"] == "GREEN"
    assert sr.gate7(covered, [], 15.0)["verdict"] == "INCOMPLETE", "保留期不够，连续 30 天站不住"
    assert sr.gate7(covered[:7], [covered[7]], 30.0)["verdict"] == "INCOMPLETE"


# ---- 样本读取与渲染 -------------------------------------------------------------

def test_load_samples_filters_by_window_and_reads_filename_date(tmp_path):
    write_sample(tmp_path, "live-concurrency-2026-11-02-c20.json", sample())
    old = sample(started_at="2026-10-01T10:00:00+0800")
    write_sample(tmp_path, "live-concurrency-2026-10-01-c20.json", old)
    rows = sr.load_samples(tmp_path, date(2026, 11, 1), date(2026, 11, 7))
    assert [r["file"] for r in rows] == ["live-concurrency-2026-11-02-c20.json"]


def test_load_samples_marks_unreadable_file_invalid(tmp_path):
    (tmp_path / "live-concurrency-2026-11-02-c20.json").write_text("{oops", encoding="utf-8")
    rows = sr.load_samples(tmp_path, date(2026, 11, 1), date(2026, 11, 7))
    assert rows[0]["verdict"] == "INVALID"


def _payload(samples=None, covered=7, retention=30.0):
    return {
        "schema": 1, "kind": "weekly",
        "window": {"start": "2026-11-03", "end": "2026-11-09", "days": 7},
        "prometheus": {"url": "http://127.0.0.1:9090", "job": "opspilot"},
        "coverage": {"covered": [f"2026-11-{d:02d}" for d in range(3, 3 + covered)],
                     "missing": [], "notes": [], "retention_days": retention,
                     "min_scrapes_per_day": 240},
        "sli": {"requests_total": 1000.0, "requests_failed": 0.0, "admission_rejected": 600.0,
                "ttft_p50": 1.0, "ttft_p95": 2.0, "ttft_p99": 2.5,
                "req_p95": 3.0, "req_p99": 4.0, "degradation_max": 0.0, "inflight_max": 32.0},
        "samples": samples or [], "gate6": sr.gate6(samples or []),
        "gate7": sr.gate7([f"2026-11-{d:02d}" for d in range(3, 3 + covered)], [], retention),
    }


def test_render_states_missing_data_instead_of_zero():
    payload = _payload()
    payload["sli"]["ttft_p95"] = None
    text = sr.render(payload, "weekly")
    assert "无数据" in text


def test_render_weekly_has_the_three_sections():
    text = sr.render(_payload(), "weekly")
    assert "# 门禁 6/7 观察周报" in text
    assert "## 2. 抓取连续性" in text and "## 3. 门禁 6" in text and "## 4. 门禁 7" in text


def test_render_monthly_uses_month_title():
    payload = _payload(covered=30)
    payload["window"] = {"start": "2026-11-01", "end": "2026-11-30", "days": 30}
    assert sr.render(payload, "monthly").startswith("# SLO 月报 · 2026-11")


# ---- main ----------------------------------------------------------------------

def _run(args, monkeypatch, client):
    monkeypatch.setattr(sr, "PromClient", lambda url: client)
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = sr.main(args)
    return code, buf.getvalue()


def test_main_stdout_writes_no_files(tmp_path, monkeypatch):
    out = tmp_path / "out"
    code, text = _run(["--end", "2026-11-09", "--out", str(out), "--stdout",
                       "--samples-dir", str(tmp_path)], monkeypatch,
                      FakeProm(values={"aiops_requests_total": 10.0, "count_over_time": 5760.0}))
    assert code == 0 and "门禁 6/7 观察周报" in text
    assert not out.exists(), "--stdout 只打印，不在磁盘留报表"


def test_main_writes_deterministic_names(tmp_path, monkeypatch):
    code, _ = _run(["--end", "2026-11-09", "--out", str(tmp_path), "--samples-dir", str(tmp_path)],
                   monkeypatch,
                   FakeProm(values={"aiops_requests_total": 10.0, "count_over_time": 5760.0}))
    assert code == 0
    assert (tmp_path / "slo-weekly-2026-11-09.md").exists()
    assert (tmp_path / "slo-weekly-2026-11-09.json").exists()
    raw = (tmp_path / "slo-weekly-2026-11-09.md").read_bytes()
    assert b"\r\n" not in raw, "统一 LF：跨平台 diff 才不会整文件翻新"


def test_main_exits_2_when_prometheus_is_unreachable(tmp_path, monkeypatch):
    code, _ = _run(["--end", "2026-11-09", "--out", str(tmp_path)], monkeypatch, FakeProm(fail=True))
    assert code == 2 and not list(tmp_path.glob("slo-*.md"))


class _Resp:
    """最小 HTTP 响应替身：`PromClient._get` 只用 read() 与上下文管理器。"""

    def __init__(self, payload):
        self._body = json.dumps(payload).encode("utf-8")

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False
