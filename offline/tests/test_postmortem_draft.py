"""复盘草稿生成器的不变式：机器只填证据、绝不写语料、绝不猜判断。

为什么这些测试必须存在：
- 「结论 → 语料」一旦自动闭环，模型输出就会变成检索事实——本仓库最贵的资产是"可信"，
  所以写入守卫与 TODO-only 判断段是**红线**，必须被测试钉住，而不是靠代码注释。
- 审计行只有统计量（没有答案正文），所以生成器**不能**假装拿到了正文；
  "无台账"必须显式写出来，而不是留白让人误以为没有引用。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import pytest

OFFLINE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(OFFLINE))
sys.path.insert(0, str(OFFLINE / "eval"))

import postmortem_draft as pmd  # noqa: E402
import dataset_audit  # noqa: E402

TRACE = "am-" + "a" * 32
FP = "f" * 32


def write_jsonl(path: Path, rows: list[dict]) -> Path:
    path.write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
        encoding="utf-8",
        newline="\n",
    )
    return path


def chat(ts: int, rid: str, **over) -> dict:
    row = {
        "ts": ts, "ev": "chat", "via": "sse", "source": "alert",
        "sub": "sre-watcher", "tenant": "tenant-internal", "level": 1,
        "q": "容器反复重启，报 connection refused", "fp": FP,
        "cache_hit": "", "mode": "hybrid", "refused": False,
        "max_level": 1, "took_ms": 120, "refs": 3, "actions": 2,
        "degrade_level": "L0", "request_id": rid, "trace_id": TRACE,
    }
    row.update(over)
    return row


def _capture(args: list[str]) -> str:
    import contextlib
    import io

    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        assert pmd.main(args) == 0
    return buf.getvalue()


def render(tmp_path: Path, rows: list[dict], ledger: list[dict] | None = None) -> str:
    audit = write_jsonl(tmp_path / "audit.jsonl", rows)
    args = ["--trace-id", TRACE, "--audit", str(audit), "--stdout"]
    if ledger is not None:
        args += ["--alert-ledger", str(write_jsonl(tmp_path / "ledger.jsonl", ledger))]
    else:
        args += ["--alert-ledger", str(tmp_path / "missing-ledger.jsonl")]
    return _capture(args)


def test_only_matching_trace_is_selected_and_ordered(tmp_path):
    audit = write_jsonl(tmp_path / "audit.jsonl", [
        chat(2000, "r2"),
        chat(1000, "r1"),
        chat(3000, "r3", trace_id="am-" + "b" * 32),
        {"ts": 1500, "ev": "search", "trace_id": TRACE},
    ])
    rows, bad = pmd.load_jsonl([audit])
    got = pmd.select_incident(rows, TRACE)
    assert [r["request_id"] for r in got] == ["r1", "r2"]
    assert bad == 0


def test_malformed_lines_are_counted_not_fatal(tmp_path):
    path = tmp_path / "audit.jsonl"
    path.write_text(
        json.dumps(chat(1000, "r1"), ensure_ascii=False) + "\n"
        + "{not json}\n"
        + "[1,2,3]\n",
        encoding="utf-8", newline="\n",
    )
    rows, bad = pmd.load_jsonl([path])
    assert len(rows) == 1 and bad == 2


def test_duplicate_rows_are_deduped(tmp_path):
    audit = write_jsonl(tmp_path / "audit.jsonl", [chat(1000, "r1"), chat(1000, "r1")])
    rows, _ = pmd.load_jsonl([audit])
    assert len(pmd.select_incident(rows, TRACE)) == 1


def test_ledger_enriches_refs_and_alert_identity(tmp_path):
    text = render(tmp_path, [chat(1000, "r1")], ledger=[{
        "incident_id": TRACE, "alert_id": "alert-1", "alertname": "ContainerDown",
        "status": "resolved", "error_code": "50012_DB_TIMEOUT",
        "refs": ["rb-102::真实签名", "rb-103::根因"],
        "ref_docs": ["rb-102", "rb-103"],
    }])
    assert "alert-1" in text
    assert "rb-102::真实签名" in text
    assert "rb-102、rb-103" in text
    assert "resolved" in text


def test_missing_ledger_is_marked_not_inferred(tmp_path):
    text = render(tmp_path, [chat(1000, "r1")])
    assert "未找到该 incident 的台账行" in text
    assert "引用 chunk：—" not in text  # 整段缺失，而不是伪造一个空清单


def test_feedback_joined_by_fingerprint(tmp_path):
    audit = write_jsonl(tmp_path / "audit2.jsonl", [
        chat(1000, "r1"),
        {"ts": 1100, "ev": "feedback", "fp": FP, "verdict": "down", "note": "没给回滚步骤"},
        {"ts": 1200, "ev": "feedback", "fp": "0" * 32, "verdict": "up"},
    ])
    out = _capture(["--trace-id", TRACE, "--audit", str(audit),
                    "--alert-ledger", str(tmp_path / "none.jsonl"), "--stdout"])
    section = re.search(r"^\*\*人工反馈\*\*.*?(?=^\*\*|^## )", out, re.S | re.M).group(0)
    assert "down：没给回滚步骤" in section
    assert "up" not in section


def test_degrade_transitions_inside_window_only(tmp_path):
    audit = write_jsonl(tmp_path / "audit.jsonl", [
        chat(1000, "r1"), chat(2000, "r2"),
        {"ts": 1500, "ev": "degrade_transition", "from": "L0", "to": "L1", "cause": "inflight"},
        {"ts": 999_999, "ev": "degrade_transition", "from": "L1", "to": "L0", "cause": "cooldown_expired"},
    ])
    out = _capture(["--trace-id", TRACE, "--audit", str(audit),
                    "--alert-ledger", str(tmp_path / "none.jsonl"), "--stdout"])
    assert "L0 → L1" in out
    assert "cooldown_expired" not in out


def test_judgment_sections_contain_only_todo(tmp_path):
    text = render(tmp_path, [chat(1000, "r1")])

    def body(title: str) -> str:
        m = re.search(rf"^## {re.escape(title)}\n(.*?)(?=^## |\Z)", text, re.S | re.M)
        assert m, title
        return m.group(1).strip()

    for title in ("事故摘要", "时间线", "根因", "处置", "验证", "教训"):
        section = body(title)
        assert section, title
        for line in [ln for ln in section.splitlines() if ln.strip()]:
            assert line.startswith(pmd.TODO), (title, line)


def test_draft_passes_the_same_sensitive_scan(tmp_path):
    text = render(tmp_path, [chat(1000, "r1")], ledger=[])
    assert dataset_audit.audit_text("draft.md", text) == []


def test_refuses_to_write_into_corpus(tmp_path, capsys):
    audit = write_jsonl(tmp_path / "audit.jsonl", [chat(1000, "r1")])
    target = OFFLINE / "corpus" / "runbooks"
    before = {p.name for p in target.iterdir()}
    code = pmd.main(["--trace-id", TRACE, "--audit", str(audit), "--out", str(target)])
    assert code == 3
    assert {p.name for p in target.iterdir()} == before
    assert "拒绝写入" in capsys.readouterr().err


def test_refuses_sensitive_query(tmp_path, capsys):
    audit = write_jsonl(tmp_path / "audit.jsonl",
                        [chat(1000, "r1", q="请联系 ops@corp.com 处理")])
    out = tmp_path / "out"
    code = pmd.main(["--trace-id", TRACE, "--audit", str(audit), "--out", str(out)])
    assert code == 3
    assert not out.exists()
    assert "敏感信息" in capsys.readouterr().err


def test_no_rows_returns_2(tmp_path, capsys):
    audit = write_jsonl(tmp_path / "audit.jsonl", [chat(1000, "r1", trace_id="other-trace-1")])
    assert pmd.main(["--trace-id", TRACE, "--audit", str(audit)]) == 2
    assert "找不到" in capsys.readouterr().err


def test_existing_draft_requires_force(tmp_path):
    audit = write_jsonl(tmp_path / "audit.jsonl", [chat(1000, "r1")])
    out = tmp_path / "out"
    base = ["--trace-id", TRACE, "--audit", str(audit), "--out", str(out)]
    assert pmd.main(base) == 0
    assert pmd.main(base) == 2
    assert pmd.main(base + ["--force"]) == 0


def test_output_is_deterministic(tmp_path):
    audit = write_jsonl(tmp_path / "audit.jsonl", [chat(1000, "r1"), chat(2000, "r2")])
    args = ["--trace-id", TRACE, "--audit", str(audit),
            "--alert-ledger", str(tmp_path / "none.jsonl"), "--stdout"]
    assert _capture(args) == _capture(args)


def test_default_filename_cannot_be_ingested_by_accident(tmp_path):
    name = pmd.default_name(TRACE, [chat(1000, "r1")])
    # chunker 从文件名前缀取 doc_id：`[a-z]+-\d+`。草稿必须匹配不上，否则两个草稿会撞 doc_id。
    assert re.match(r"([a-z]+-\d+)", Path(name).stem) is None
    assert name.startswith("draft_") and name.endswith(".md")


def test_writes_utf8_lf(tmp_path):
    audit = write_jsonl(tmp_path / "audit.jsonl", [chat(1000, "r1")])
    out = tmp_path / "out"
    assert pmd.main(["--trace-id", TRACE, "--audit", str(audit), "--out", str(out)]) == 0
    files = list(out.glob("*.md"))
    assert len(files) == 1
    raw = files[0].read_bytes()
    assert b"\r\n" not in raw
    assert "TODO(人)" in raw.decode("utf-8")
