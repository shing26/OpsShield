"""反馈复核队列的不变式：只排序不处置、指纹级归并不假装知道"哪次生成"、证据缺失要显式写出来。

为什么这些测试必须存在：
- 队列是"人复核"的入口。它一旦悄悄丢掉反馈（无 fp 的行、轮转外的指纹）或把 up 票混进 down，
  人看到的优先级就是错的——而错误的优先级比没有队列更糟，因为它看起来可信。
- 本工具与 `postmortem_draft.py` 同一条红线：只读审计、不写语料。测试锁住"不处置"，
  否则将来一次"顺手自动改权重"的提交会把闭环变成自证。
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
from pathlib import Path

OFFLINE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(OFFLINE))

import feedback_review as fr  # noqa: E402

FP_A = "a" * 32
FP_B = "b" * 32
FP_C = "c" * 32


def write_jsonl(path: Path, rows: list[dict]) -> Path:
    path.write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
        encoding="utf-8",
        newline="\n",
    )
    return path


def chat(ts: int, fp: str = FP_A, **over) -> dict:
    row = {
        "ts": ts, "ev": "chat", "fp": fp, "q": "订单 123 超时",
        "refused": False, "source": "alert", "degrade_level": "L0",
        "trace_id": "am-1",
    }
    row.update(over)
    return row


def fb(ts: int, fp: str = FP_A, verdict: str = "down", **over) -> dict:
    row = {"ts": ts, "ev": "feedback", "fp": fp, "verdict": verdict}
    row.update(over)
    return row


def run(rows: list[dict], ledger: list[dict] | None = None) -> tuple[list[fr.Item], dict, str]:
    queue, stats = fr.collect(rows, ledger or [])
    return queue, stats, fr.render(queue, stats, fr.suspect_docs(queue))


def _main(args: list[str]) -> tuple[int, str]:
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = fr.main(args)
    return code, buf.getvalue()


# ---- 归并口径 -------------------------------------------------------------------

def test_only_down_fingerprints_enter_the_queue():
    rows = [fb(1, FP_A, "down"), fb(2, FP_B, "up"), chat(3, FP_A), chat(4, FP_B)]
    queue, stats, _ = run(rows)
    assert [it.fp for it in queue] == [FP_A], "up 票只进统计，不进复核队列"
    assert stats["up"] == 1 and stats["down"] == 1
    assert stats["fingerprints"] == 2, "统计按指纹计，up-only 指纹也算进覆盖数"


def test_repeat_down_is_p1_even_when_answered():
    rows = [chat(1, FP_A), fb(2, FP_A, "down"), fb(3, FP_A, "down"), chat(4, FP_B), fb(5, FP_B, "down")]
    queue, _, _ = run(rows)
    assert [it.priority for it in queue] == [fr.P_REPEAT, fr.P_ANSWERED]
    assert queue[0].down == 2 and queue[0].label.startswith("P1")


def test_refused_only_down_is_p3_not_p2():
    rows = [chat(1, FP_B, refused=True), fb(2, FP_B, "down")]
    queue, _, _ = run(rows)
    assert queue[0].priority == fr.P_REFUSED, "只否了拒答，先别按'错答'处理——可能是过度拒答"


def test_mixed_verdicts_are_flagged_not_averaged():
    rows = [chat(1, FP_A), fb(2, FP_A, "down"), fb(3, FP_A, "up"), fb(4, FP_A, "down")]
    queue, _, text = run(rows)
    assert queue[0].down == 2 and queue[0].up == 1
    assert queue[0].mixed is True
    assert "判断有分歧" in text


def test_feedback_without_fingerprint_is_dropped_not_bucketed():
    rows = [{"ts": 1, "ev": "feedback", "fp": "", "verdict": "down"}, fb(2, FP_A, "down"), chat(3)]
    queue, stats, _ = run(rows)
    assert stats["feedback_events"] == 1, "无 fp 的反馈无法归档，不能塞进 unknown 桶伪装成归并过"
    assert [it.fp for it in queue] == [FP_A]


def test_unknown_verdict_is_counted_but_never_queued():
    rows = [chat(1), fb(2, FP_A, "meh")]
    queue, stats, _ = run(rows)
    assert queue == [] and stats["unknown_verdict"] == 1
    assert stats["down"] == 0 and stats["up"] == 0


def test_notes_are_kept_in_order_and_whitespace_collapsed():
    rows = [chat(1), fb(2, FP_A, "down", note="错误码是编的\n\n  且引用了不存在的文档")]
    queue, _, text = run(rows)
    assert queue[0].notes == ["错误码是编的 且引用了不存在的文档"]
    assert "引用了不存在的文档" in text


def test_queries_are_deduped_and_capped():
    rows = [chat(i, FP_A, q=f"问题 {i}") for i in range(1, 7)] + [fb(9, FP_A, "down")]
    queue, _, _ = run(rows)
    assert len(queue[0].queries) == fr.QUERY_MAX
    assert queue[0].queries[0] == "问题 1", "按时间正序，取最早几种问法"


# ---- 上下文与证据 ---------------------------------------------------------------

def test_feedback_without_chat_row_still_queues_but_says_so():
    rows = [fb(1, FP_A, "down"), fb(2, FP_A, "down")]
    queue, stats, text = run(rows)
    assert len(queue) == 1 and stats["unmatched_context"] == 1
    assert "审计里没有该指纹的请求行" in text, "上下文缺失必须显式说，不能留白让人以为系统没答过"


def test_answered_and_refused_counts_come_from_chat_rows():
    rows = [chat(1, FP_A), chat(2, FP_A, refused=True), chat(3, FP_A), fb(4, FP_A, "down")]
    queue, _, _ = run(rows)
    assert queue[0].answered == 2 and queue[0].refused == 1


def test_ledger_enriches_refs_by_fingerprint():
    rows = [chat(1, FP_A), fb(2, FP_A, "down")]
    ledger = [{"fp": FP_A, "ref_docs": ["rb-101", "es-3"], "alert_id": "al-9"}]
    queue, _, text = run(rows, ledger)
    assert queue[0].ref_docs == ["rb-101", "es-3"]
    assert queue[0].alert_ids == ["al-9"]
    assert "rb-101" in text


def test_missing_ledger_is_stated_not_left_blank():
    rows = [chat(1, FP_A), fb(2, FP_A, "down")]
    _, _, text = run(rows)
    assert "引用清单：无" in text


def test_suspect_docs_rank_by_down_votes_and_ignore_up_only_fingerprints():
    rows = [
        chat(1, FP_A), fb(2, FP_A, "down"), fb(3, FP_A, "down"),
        chat(4, FP_B), fb(5, FP_B, "down"),
        chat(6, FP_C), fb(7, FP_C, "up"),
    ]
    ledger = [
        {"fp": FP_A, "ref_docs": ["rb-101", "es-3"]},
        {"fp": FP_B, "ref_docs": ["rb-101"]},
        {"fp": FP_C, "ref_docs": ["rb-999"]},
    ]
    docs = fr.suspect_docs(fr.collect(rows, ledger)[0])
    assert docs[0] == {"doc": "rb-101", "down": 3, "fingerprints": 2}
    assert [d["doc"] for d in docs] == ["rb-101", "es-3"], "up-only 指纹引用的文档不是嫌疑对象"


# ---- 输入宽容与输出形态 ---------------------------------------------------------

def test_bad_lines_are_skipped_not_fatal(tmp_path):
    path = tmp_path / "audit.jsonl"
    path.write_text(
        "{not json\n" + json.dumps(fb(1, FP_A, "down")) + "\n\n" + json.dumps(chat(2)) + "\n",
        encoding="utf-8",
    )
    rows, bad = fr.load_jsonl([path])
    assert bad == 1 and len(rows) == 2
    code, out = _main(["--audit", str(path), "--out", str(tmp_path)])
    assert code == 0


def test_audit_dir_reads_rotated_slices(tmp_path):
    write_jsonl(tmp_path / "audit.1.jsonl", [chat(1)])
    write_jsonl(tmp_path / "audit.2.jsonl", [fb(2, FP_A, "down")])
    code, _ = _main(["--audit", str(tmp_path), "--alert-ledger", str(tmp_path / "none"), "--out", str(tmp_path)])
    assert code == 0
    report = (tmp_path / "queue_1970-01-01.md").read_text(encoding="utf-8")
    assert "P2 作答被否" in report, "轮转片拼起来才是完整的一次反馈：只读单片会丢掉 chat 上下文"


def test_no_feedback_rows_exits_2(tmp_path):
    path = write_jsonl(tmp_path / "audit.jsonl", [chat(1)])
    code, _ = _main(["--audit", str(path), "--out", str(tmp_path)])
    assert code == 2, "没有反馈是'空'不是'错'，但仍要能被脚本识别（与有反馈但队列为空区分开）"


def test_empty_queue_still_renders_a_report(tmp_path):
    path = write_jsonl(tmp_path / "audit.jsonl", [chat(1), fb(2, FP_A, "up")])
    code, _ = _main(["--audit", str(path), "--out", str(tmp_path)])
    assert code == 0
    text = (tmp_path / "queue_1970-01-01.md").read_text(encoding="utf-8")
    assert "队列为空" in text and "aiops_feedback_total" in text


def test_filename_comes_from_the_data_window_not_the_clock(tmp_path):
    path = write_jsonl(tmp_path / "audit.jsonl", [chat(1), fb(1_700_000_000_000, FP_A, "down")])
    _main(["--audit", str(path), "--out", str(tmp_path)])
    assert (tmp_path / "queue_2023-11-15.md").exists()
    assert len(list(tmp_path.glob("queue_*.md"))) == 1


def test_render_is_deterministic():
    rows = [chat(1), fb(2, FP_A, "down", note="x"), fb(3, FP_A, "down")]
    assert run(rows)[2] == run(rows)[2]


def test_json_output_is_machine_readable(tmp_path):
    path = write_jsonl(tmp_path / "audit.jsonl", [chat(1), fb(2, FP_A, "down", note="编的")])
    code, out = _main(["--audit", str(path), "--json", "--stdout"])
    assert code == 0
    payload = json.loads(out)
    assert payload["stats"]["queue"] == 1
    assert payload["queue"][0]["fingerprint"] == FP_A
    assert payload["queue"][0]["priority"] == fr.P_ANSWERED
    assert payload["queue"][0]["notes"] == ["编的"]


def test_stdout_writes_no_file(tmp_path):
    path = write_jsonl(tmp_path / "audit.jsonl", [chat(1), fb(2, FP_A, "down")])
    out_dir = tmp_path / "out"
    code, _ = _main(["--audit", str(path), "--out", str(out_dir), "--stdout"])
    assert code == 0
    assert not out_dir.exists(), "--stdout 只打印：不得悄悄在磁盘上留下报表"


def test_pipe_in_query_does_not_break_the_table():
    rows = [chat(1, q="a|b"), fb(2, FP_A, "down")]
    _, _, text = run(rows)
    table = [ln for ln in text.splitlines() if ln.startswith("| P")]
    assert len(table) == 1 and "a\\|b" in table[0]
