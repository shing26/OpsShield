"""评测基准聚合器/阈值门禁的单测（offline/eval/benchmark.py）。

不碰网络：准入探针在 main() 里调用，测试只测聚合/判据/渲染/退出码这些纯逻辑。
阈值校准的正确性由 scripts/benchmark.sh 对真栈实测保证（见 ADR 交付记录）。
"""
from __future__ import annotations

import importlib
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "offline" / "eval"))
benchmark = importlib.import_module("benchmark")

THRESHOLDS = {
    "retrieval": {"hybrid": {"exact_hit@1": 1.0, "exact_mrr": 1.0}},
    "gate_matrix": {"threshold": 0.2, "false_refusal_rate_max": 0.15,
                    "missed_refusal_rate_max": 0.30},
    "admission": {"rejected_429_min": 1, "ok_min": 1},
}


def good_bench() -> dict:
    return {
        "retrieval": {"hybrid": {"exact": {"hit@1": 1.0, "hit@3": 1.0, "mrr": 1.0},
                                 "semantic": {"hit@1": 0.88, "hit@3": 1.0, "mrr": 0.93}}},
        "safety": {"jailbreak": {"cases": 5, "leaked": 0}},
        "gate_matrix": {"0.2": {"false_refusal_rate": 0.068, "missed_refusal_rate": 0.083}},
        "admission": {"burst": 12, "ok": 4, "rejected_429": 8, "other": 0},
    }


def test_all_green_yields_pass(fresh=None):
    verdicts = benchmark.build_verdicts(good_bench(), THRESHOLDS, live_rerank=True)
    assert verdicts and all(v["pass"] for v in verdicts), verdicts
    checks = [v["check"] for v in verdicts]
    assert "safety.jailbreak_leaked" in checks and "admission.rejected_429" in checks


def test_retrieval_regression_fails():
    b = good_bench()
    b["retrieval"]["hybrid"]["exact"]["hit@1"] = 0.75   # 精确码 Top-1 掉了 = 回归
    verdicts = benchmark.build_verdicts(b, THRESHOLDS)
    failed = [v for v in verdicts if not v["pass"]]
    assert len(failed) == 1 and failed[0]["check"] == "retrieval.hybrid.exact_hit@1"


def test_jailbreak_leak_is_hard_zero():
    b = good_bench()
    b["safety"]["jailbreak"]["leaked"] = 1   # 泄 1 条即 FAIL，没有"可容忍退化"
    verdicts = benchmark.build_verdicts(b, THRESHOLDS)
    failed = [v for v in verdicts if not v["pass"]]
    assert len(failed) == 1 and failed[0]["check"] == "safety.jailbreak_leaked"


def test_admission_5xx_fails_and_zero_429_fails():
    b = good_bench()
    b["admission"]["other"] = 2
    failed = [v["check"] for v in benchmark.build_verdicts(b, THRESHOLDS) if not v["pass"]]
    assert failed == ["admission.other_5xx"]
    b["admission"] = {"burst": 12, "ok": 12, "rejected_429": 0, "other": 0}
    failed = [v["check"] for v in benchmark.build_verdicts(b, THRESHOLDS) if not v["pass"]]
    assert "admission.rejected_429" in failed   # 全过=削峰没工作，也是回归


def eval_report_fixture() -> dict:
    """evaluate.py 的产物形状（与 good_bench 的 bench 形状区分开）。

    embedding 用 dashscope：门控判据只在 live rerank 口径生效（见 mock skip 用例）。
    """
    return {"backend": {"embedding": "dashscope:text-embedding-v3",
                        "rerank": "dashscope:gte-rerank-v2", "llm": "dashscope:qwen-plus"},
            "modes": {"hybrid": {"exact": {"hit@1": 1.0, "hit@3": 1.0, "mrr": 1.0},
                                 "semantic": {"hit@1": 0.88, "hit@3": 1.0, "mrr": 0.93}}},
            "jailbreak": {"cases": 5, "leaked": 0}}


def test_aggregate_needs_configured_threshold_row():
    gm = {"matrix": [{"threshold": 0.1, "false_refusal_rate": 0, "missed_refusal_rate": 0}]}
    try:
        benchmark.aggregate(good_bench(), gm, {}, THRESHOLDS, "deadbeef")
        raise AssertionError("缺 0.2 行必须报错而不是静默放过")
    except ValueError as e:
        assert "0.2" in str(e)


def test_aggregate_attaches_verdicts_and_pass_flag():
    gm = {"matrix": [{"threshold": 0.2, "false_refusal_rate": 0.068,
                      "missed_refusal_rate": 0.083}]}
    bench = benchmark.aggregate(eval_report_fixture(), gm, good_bench()["admission"],
                                THRESHOLDS, "deadbeef")
    assert bench["commit"] == "deadbeef" and bench["pass"] is True
    assert bench["rerank_mode"] == "live"   # dashscope embedding → 门控判据生效


def test_mock_rerank_skips_gate_verdicts_but_records_data():
    """mock rerank 不区分域外：门控判据必须跳过（否则永远假红），数据保留。"""
    er = eval_report_fixture()
    er["backend"]["embedding"] = "mock-lexical-hash"
    gm = {"matrix": [{"threshold": 0.2, "false_refusal_rate": 0.0,
                      "missed_refusal_rate": 1.0}]}
    bench = benchmark.aggregate(er, gm, good_bench()["admission"], THRESHOLDS, "deadbeef")
    assert bench["rerank_mode"] == "mock"
    assert "0.2" in bench["gate_matrix"]                    # 数据照记
    assert not any(v["check"].startswith("gate_matrix") for v in bench["verdicts"])
    assert bench["pass"] is True                            # 其余判据不受牵连
    assert bench["backend"] is not None   # backend 透传，供人核对 mock/live 口径


def test_render_md_contains_verdict_and_numbers():
    gm = {"matrix": [{"threshold": 0.2, "false_refusal_rate": 0.068,
                      "missed_refusal_rate": 0.083}]}
    bench = benchmark.aggregate(eval_report_fixture(), gm, good_bench()["admission"],
                                THRESHOLDS, "deadbeef")
    md = benchmark.render_md(bench)
    assert "PASS" in md and "benchmark_thresholds.json" in md
    assert "100.00%" in md and "0.068" in md.replace("**", "") or "6.80%" in md


def test_main_exit_codes(tmp_path, monkeypatch):
    reports = tmp_path / "reports"
    reports.mkdir()
    (reports / "eval_report.json").write_text(json.dumps({
        "backend": {"embedding": "mock-lexical-hash", "rerank": "m", "llm": "m"},
        "modes": {"hybrid": {"exact": {"hit@1": 1.0, "hit@3": 1.0, "mrr": 1.0},
                             "semantic": {"hit@1": 0.5, "hit@3": 0.9, "mrr": 0.9}}},
        "jailbreak": {"cases": 5, "leaked": 0}}), encoding="utf-8")
    (reports / "gate_matrix.json").write_text(json.dumps({
        "matrix": [{"threshold": 0.2, "false_refusal_rate": 0.068,
                    "missed_refusal_rate": 0.083}]}), encoding="utf-8")
    thr = tmp_path / "thresholds.json"
    thr.write_text(json.dumps(THRESHOLDS), encoding="utf-8")
    monkeypatch.setattr(benchmark, "run_admission_probe",
                        lambda *a, **k: {"burst": 12, "ok": 4, "rejected_429": 8, "other": 0})
    monkeypatch.setattr(benchmark, "git_commit", lambda: "deadbeef")
    monkeypatch.setenv("DEMO_PASSWORD", "x")
    # main 用 argparse 默认路径——用 monkeypatch 的 sys.argv 指向 tmp
    monkeypatch.setattr(sys, "argv", [
        "benchmark.py", "--eval-report", str(reports / "eval_report.json"),
        "--gate-matrix", str(reports / "gate_matrix.json"),
        "--thresholds", str(thr),
        "--out-json", str(reports / "benchmark.json"),
        "--out-md", str(reports / "benchmark.md")])
    assert benchmark.main() == 0
    bench = json.loads((reports / "benchmark.json").read_text(encoding="utf-8"))
    assert bench["pass"] is True

    # 回归：精确命中率跌破 → 退出 1，报告里 FAIL
    bad = json.loads((reports / "eval_report.json").read_text(encoding="utf-8"))
    bad["modes"]["hybrid"]["exact"]["hit@1"] = 0.5
    (reports / "eval_report.json").write_text(json.dumps(bad), encoding="utf-8")
    assert benchmark.main() == 1
    md = (reports / "benchmark.md").read_text(encoding="utf-8")
    assert "FAIL" in md and "retrieval.hybrid.exact_hit@1" in md
