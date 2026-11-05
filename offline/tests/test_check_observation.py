"""门禁 6/7 收口检查器：证据不足时绝不判绿，坏证据绝不跳过。

这条测试守的是"门禁 1 的教训"：报表每天在生成本身不是证据，**被核过**才是。
聚合器如果把断档漏成"连续"、把 UNPROVEN 算成通过，第 30 天就会开出一张漂亮的假绿。
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent.parent / "scripts"
sys.path.insert(0, str(SCRIPTS))

import check_observation as co  # noqa: E402


def report(days: list[str], samples: list[dict] | None = None,
           retention: float | None = 30.0) -> dict:
    return {
        "coverage": {"covered": days, "missing": [], "retention_days": retention},
        "samples": samples or [],
    }


def passing(n: int) -> list[dict]:
    return [{"file": f"live-concurrency-2026-11-0{i + 1}-c20.json", "verdict": "PASS"}
            for i in range(n)]


def write(tmp: Path, name: str, data) -> Path:
    p = tmp / name
    p.write_text(data if isinstance(data, str) else json.dumps(data, ensure_ascii=False),
                 encoding="utf-8")
    return p


def days_between(start: str, n: int) -> list[str]:
    from datetime import date, timedelta
    d0 = date.fromisoformat(start)
    return [(d0 + timedelta(days=i)).isoformat() for i in range(n)]


def run(args: list[str]) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = co.main(args)
    return code, out.getvalue(), err.getvalue()


# ---- 无证据 / 证据不足 -----------------------------------------------------------

def test_no_reports_is_incomplete_not_red(tmp_path):
    code, _, err = run(["--dir", str(tmp_path)])
    assert code == 2 and "证据不足" in err


def test_one_week_is_incomplete(tmp_path):
    write(tmp_path, "slo-weekly-2026-11-09.json", report(days_between("2026-11-03", 7), passing(3)))
    code, out, _ = run(["--dir", str(tmp_path)])
    assert code == 2, "观察期没走完不是违规，退出码 2 让 CI 区分'还没到'与'有问题'"
    assert "门禁 6：INCOMPLETE" in out and "门禁 7：INCOMPLETE" in out


# ---- 绿 -------------------------------------------------------------------------

def test_thirty_contiguous_days_and_twelve_passing_samples_is_green(tmp_path):
    write(tmp_path, "slo-monthly-2026-11.json",
          report(days_between("2026-10-06", 30), passing(12)))
    code, out, _ = run(["--dir", str(tmp_path)])
    assert code == 0
    assert "门禁 6：GREEN" in out and "门禁 7：GREEN" in out


def test_weekly_and_monthly_overlap_does_not_double_count(tmp_path):
    days = days_between("2026-10-06", 30)
    write(tmp_path, "slo-weekly-2026-10-12.json", report(days[:7], passing(3)))
    write(tmp_path, "slo-monthly-2026-11.json", report(days, passing(12)))
    agg = co.aggregate(co.load_reports(tmp_path)[0])
    assert len(agg["days"]) == 30, "同一天出现在周报和月报里只算一天"
    assert agg["passing_records"] == 24, "同一个样本文件出现在两份报表里只算一次"


# ---- 红 -------------------------------------------------------------------------

def test_a_single_gap_day_is_red(tmp_path):
    days = days_between("2026-10-06", 30)
    days.remove("2026-10-20")
    write(tmp_path, "slo-monthly-2026-11.json", report(days, passing(12)))
    code, out, _ = run(["--dir", str(tmp_path)])
    assert code == 1, "缺一天'连续 30 天'就不成立，不能因为总数还够就放过"
    assert "门禁 7：RED" in out and "2026-10-20" in out


def test_a_single_failed_sample_is_red_even_with_enough_passes(tmp_path):
    samples = passing(12) + [{"file": "live-concurrency-2026-11-05-c20.json", "verdict": "FAIL"}]
    write(tmp_path, "slo-monthly-2026-11.json", report(days_between("2026-10-06", 30), samples))
    code, out, _ = run(["--dir", str(tmp_path)])
    assert code == 1 and "门禁 6：RED" in out
    assert "live-concurrency-2026-11-05-c20.json" in out, "红要指名是哪个样本"


def test_short_retention_is_red(tmp_path):
    write(tmp_path, "slo-monthly-2026-11.json",
          report(days_between("2026-10-06", 30), passing(12), retention=15.0))
    code, out, _ = run(["--dir", str(tmp_path)])
    assert code == 1 and "保留期 15.0 天" in out


def test_unproven_samples_never_count_as_records(tmp_path):
    samples = [{"file": f"live-concurrency-2026-10-{d:02d}-c20.json", "verdict": "UNPROVEN"}
               for d in range(1, 15)]
    agg = co.aggregate([report(days_between("2026-10-06", 30), samples)])
    assert agg["passing_records"] == 0 and agg["samples_unproven"] == 14


def test_corrupt_report_fails_the_run(tmp_path):
    write(tmp_path, "slo-weekly-2026-11-09.json", "{not json")
    write(tmp_path, "slo-monthly-2026-11.json", report(days_between("2026-10-06", 30), passing(12)))
    code, _, err = run(["--dir", str(tmp_path)])
    assert code == 1 and "报表读取失败" in err, "坏证据不能被跳过，否则删掉一份报表就能让门禁变绿"


def test_non_slo_json_files_are_ignored(tmp_path):
    write(tmp_path, "slo-weekly-2026-11-09.json", report(days_between("2026-11-03", 7), passing(3)))
    write(tmp_path, "notes.json", "{still not json")
    code, _, _ = run(["--dir", str(tmp_path)])
    assert code == 2, "只认 slo-*.json：同目录的其他产物不该拖垮判定"


# ---- 结构 -----------------------------------------------------------------------

def test_gaps_lists_every_missing_day():
    assert co._gaps(["2026-11-01", "2026-11-02", "2026-11-05"]) == [
        "2026-11-03", "2026-11-04"]
    assert co._gaps([]) == [] and co._gaps(["2026-11-01"]) == []


def test_json_mode_is_machine_readable(tmp_path):
    write(tmp_path, "slo-monthly-2026-11.json", report(days_between("2026-10-06", 30), passing(12)))
    code, out, _ = run(["--dir", str(tmp_path), "--json"])
    payload = json.loads(out)
    assert code == 0
    assert payload["gate6"] == "GREEN" and payload["gate7"] == "GREEN"
    assert payload["aggregate"]["required_records"] == 24
