"""真实语料的版本身份回归锁（S3 AC1/AC2/AC4/AC5 的离线半边）。

只在纯函数上测摘要不够：字段没被写进 chunk、或同一文档各 chunk 各算一份，
单测仍会全绿而线上引用全是"版本未知"。这里直接跑真实语料构建。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

CHUNKERS = Path(__file__).parent.parent / "chunkers"
sys.path.insert(0, str(CHUNKERS))
import build_chunks  # noqa: E402
import doc_digest as dd  # noqa: E402
import markdown_tree_chunker as md  # noqa: E402
import openapi_ast_chunker as oa  # noqa: E402

CORPUS = Path(__file__).parent.parent / "corpus"


@pytest.fixture(scope="module")
def chunks() -> list[dict]:
    return build_chunks.build(CORPUS)


def test_corpus_still_builds_459_chunks(chunks: list[dict]):
    assert len(chunks) == 459, f"语料行数漂移: {len(chunks)}（S3 契约要求只加字段、不增删 chunk）"


def test_every_chunk_has_legal_doc_digest_and_reviewed_key(chunks: list[dict]):
    for c in chunks:
        assert dd.is_digest(c["metadata"]["doc_digest"]), c["chunk_id"]
        assert "reviewed_at" in c["metadata"], c["chunk_id"]
        assert c["metadata"]["reviewed_at"] is None or isinstance(c["metadata"]["reviewed_at"], str)


def test_same_doc_shares_one_digest(chunks: list[dict]):
    by_doc: dict[str, set[str]] = {}
    for c in chunks:
        by_doc.setdefault(c["doc_id"], set()).add(c["metadata"]["doc_digest"])
    bad = {d: v for d, v in by_doc.items() if len(v) != 1}
    assert not bad, f"同一文档出现多个版本: {bad}"


def test_openapi_chunks_share_digest():
    spec = json.loads((CORPUS / "openapi" / "order-center-api.json").read_text(encoding="utf-8"))
    cs = oa.chunk_openapi(spec)
    assert {c["metadata"]["doc_digest"] for c in cs} == {dd.openapi_doc_digest(spec)}
    assert all(c["metadata"]["reviewed_at"] is None for c in cs)


def test_doc_digest_ignores_front_matter_change_only():
    """只改 front matter（如 reviewed_at）不得改变正文版本。"""
    raw_a = "---\ndoc_id: rb-999\nservice: s\nreviewed_at: 2026-09-16\n---\n\n# 标题\n\n正文\n"
    raw_b = "---\ndoc_id: rb-999\nservice: s\nreviewed_at: 2026-11-26\n---\n\n# 标题\n\n正文\n"
    a = md.chunk_markdown(raw_a, "rb-999", where="rb-999.md")
    b = md.chunk_markdown(raw_b, "rb-999", where="rb-999.md")
    assert a[0]["metadata"]["doc_digest"] == b[0]["metadata"]["doc_digest"]
    assert a[0]["metadata"]["reviewed_at"] == "2026-09-16"
    assert b[0]["metadata"]["reviewed_at"] == "2026-11-26"


def test_bad_reviewed_at_names_the_source_file():
    raw = "---\ndoc_id: rb-999\nreviewed_at: 2026-02-30\n---\n\n# 标题\n\n正文\n"
    with pytest.raises(ValueError, match="rb-999-broken.md"):
        md.chunk_markdown(raw, "rb-999", where="rb-999-broken.md")


def test_build_rejects_same_doc_with_two_digests():
    with pytest.raises(ValueError, match="同一文档出现多个 doc_digest"):
        build_chunks._assert_doc_digests([
            {"chunk_id": "rb-001::a", "doc_id": "rb-001", "metadata": {"doc_digest": "a" * 12}},
            {"chunk_id": "rb-001::b", "doc_id": "rb-001", "metadata": {"doc_digest": "b" * 12}},
        ])


def test_build_rejects_malformed_digest():
    with pytest.raises(ValueError, match="缺少合法 doc_digest"):
        build_chunks._assert_doc_digests([
            {"chunk_id": "rb-001::a", "doc_id": "rb-001", "metadata": {"doc_digest": "ABC"}},
        ])
