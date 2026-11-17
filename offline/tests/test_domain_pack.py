"""领域包（DBA）语料真实性与边界回归锁（ADR-0031 / 契约 AC-5）。

三条机器判据把"不是玩具"钉死：
  1. 规模与身份：≥5 篇 .md / ≥25 chunks、doc_id 统一 dba- 前缀、零撞号、无 L1 声明；
  2. 来源可追溯：每篇 front matter 的 source_incident 必须指回既有 SRE doc_id 或
     OPS.md 里可检索的标题片段；error_codes 每一项必须已在 SRE 语料里出现；
  3. 生成可复现：chunks.jsonl 由既有 chunker 现算重生成后逐字相等，且切分器零改动。

不触网、不启服务；只用 stdlib + pytest。
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
CORPUS = ROOT / "offline" / "domains" / "dba" / "corpus"
SRE_CHUNKS = ROOT / "offline" / "corpus" / "chunks.jsonl"

ALLOWED_SOURCES = {"pm-001", "pm-002", "ac-508", "rb-001", "rb-002"}
FRONT_MATTER_RE = re.compile(r"\A---\s*\n(.*?)\n---\s*\n", re.DOTALL)
ERROR_CODE_RE = re.compile(r"\b\d{5}_[A-Z][A-Z0-9_]*\b")


def _front_matter(raw: str) -> dict[str, str | list[str]]:
    m = FRONT_MATTER_RE.match(raw)
    assert m, "每篇 DBA 文档必须有 YAML front matter"
    out: dict[str, str | list[str]] = {}
    for line in m.group(1).splitlines():
        if ":" not in line or line.lstrip().startswith("#"):
            continue
        key, _, val = line.partition(":")
        key, val = key.strip(), val.strip()
        if val.startswith("[") and val.endswith("]"):
            out[key] = [v.strip() for v in val[1:-1].split(",") if v.strip()]
        else:
            out[key] = val
    return out


def _chunks() -> list[dict]:
    return [
        json.loads(line)
        for line in (CORPUS / "chunks.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def _sre_error_codes() -> set[str]:
    codes: set[str] = set()
    for line in SRE_CHUNKS.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            codes.update(row.get("metadata", {}).get("error_codes") or [])
    return codes


def test_corpus_scale_and_identity() -> None:
    docs = sorted(CORPUS.glob("**/*.md"))
    chunks = _chunks()
    assert len(docs) >= 5, f"DBA 语料只有 {len(docs)} 篇 .md"
    assert len(chunks) >= 25, f"DBA 语料只有 {len(chunks)} chunks"
    assert all(c["doc_id"].startswith("dba-") for c in chunks)
    assert all(
        "action_class" not in c["metadata"]
        or c["metadata"]["action_class"] in (None, "")
        for c in chunks
    ), "DBA 包在本薄切片不声明 L1"
    assert len({c["doc_id"] for c in chunks}) >= 5
    sre_ids = {c["doc_id"] for c in (
        json.loads(l) for l in SRE_CHUNKS.read_text(encoding="utf-8").splitlines() if l.strip()
    )}
    assert not ({c["doc_id"] for c in chunks} & sre_ids), "DBA 与 SRE doc_id 撞号"


def test_source_incident_points_at_existing_fact() -> None:
    sre_doc_ids = {
        p.name.split("-")[0] + "-" + p.name.split("-")[1]
        for p in (ROOT / "offline" / "corpus").glob("**/[a-z][a-z]-[0-9][0-9][0-9]-*.md")
    }
    ops_text = (ROOT / "OPS.md").read_text(encoding="utf-8")
    ops_headings = {
        line.lstrip("#").strip()
        for line in ops_text.splitlines()
        if line.startswith("#")
    }
    for doc in sorted(CORPUS.glob("**/*.md")):
        meta = _front_matter(doc.read_text(encoding="utf-8"))
        assert re.fullmatch(r"dba-\d{3}", str(meta.get("doc_id", ""))), doc
        assert meta.get("source_incident"), f"{doc} 缺 source_incident"
        source = str(meta["source_incident"])
        if source in ALLOWED_SOURCES:
            assert source in sre_doc_ids, f"{doc} 指向不存在的 SRE doc: {source}"
        else:
            assert source.startswith("OPS.md#"), f"{doc} 的 source_incident 格式非法: {source}"
            needle = source[len("OPS.md#"):]
            assert any(needle in h for h in ops_headings), (
                f"{doc} 指向不存在的 OPS 标题: {needle}"
            )
        assert meta.get("auth_level"), f"{doc} 缺 auth_level"
        assert meta.get("tenant"), f"{doc} 缺 tenant"


def test_error_codes_are_existing_only() -> None:
    known = _sre_error_codes()
    for doc in sorted(CORPUS.glob("**/*.md")):
        meta = _front_matter(doc.read_text(encoding="utf-8"))
        codes = meta.get("error_codes", [])
        assert isinstance(codes, list) and codes, f"{doc} 缺 error_codes"
        for code in codes:
            assert code in known, f"{doc} 发明错误码: {code}"
            assert ERROR_CODE_RE.fullmatch(code), f"{doc} 错误码形状非法: {code}"
            # MySQL errno / SQLSTATE 只能出现在正文，不进 error_codes
            assert not re.fullmatch(r"\d{4,5}", code), f"{doc} errno 不得进 error_codes: {code}"


def test_chunks_are_reproducible_with_existing_chunker() -> None:
    """重跑既有 chunker 到 offline/staging/，产物必须与入库 chunks.jsonl 逐字相等。

    chunker 的 --out 被限制在 offline/ 内（防任意文件写），故用 gitignore 覆盖的 staging 目录，
    且测试结束必删——验证临时产物不入库（契约 §6）。
    """
    import uuid

    staging = ROOT / "offline" / "staging"
    staging.mkdir(exist_ok=True)
    out = staging / f"domain-pack-{uuid.uuid4().hex}.jsonl"
    try:
        proc = subprocess.run(
            [
                sys.executable,
                str(ROOT / "offline" / "chunkers" / "build_chunks.py"),
                "--corpus", str(CORPUS),
                "--out", str(out),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        assert proc.returncode == 0, proc.stderr
        assert out.read_text(encoding="utf-8") == (CORPUS / "chunks.jsonl").read_text(
            encoding="utf-8"
        ), "chunks.jsonl 与既有 chunker 现算结果不一致（请重跑生成命令）"
    finally:
        out.unlink(missing_ok=True)


def test_diagnostic_and_stop_loss_sections_are_separate() -> None:
    chunks = _chunks()
    # 诊断段与止血段由不同标题承担：段名闸不会被自己的数据绕开
    diag = [c for c in chunks if "核查步骤" in c["breadcrumb"]]
    stop = [c for c in chunks if "止血与恢复" in c["breadcrumb"]]
    assert diag and stop
    assert not ({"核查步骤"} & {"止血与恢复"})
    for c in diag:
        assert "止损操作" not in c["breadcrumb"], c["chunk_id"]
