"""文档版本身份与审查日期的纯函数锁（S3 引用溯源）。

这两个判据一旦漂移，引用面板会静默给出错误的"版本"或"新鲜度"：
- 摘要若不忽略行尾/front matter，换行或一次签字就会制造假版本跳变；
- `reviewed_at` 若把非法值当缺失，错误日期会伪装成"未标注"而无人发现。
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "chunkers"))
import doc_digest as dd  # noqa: E402


def test_digest_is_twelve_lower_hex():
    d = dd.digest_text("abc")
    assert dd.is_digest(d)
    assert len(d) == 12 and d == d.lower()


def test_markdown_digest_ignores_line_endings_and_outer_newlines():
    body = "# 标题\n\n排查步骤\n"
    assert dd.markdown_doc_digest(body) == dd.markdown_doc_digest(body.replace("\n", "\r\n"))
    assert dd.markdown_doc_digest(body) == dd.markdown_doc_digest("\n\n" + body + "\n\n")


def test_markdown_digest_changes_with_one_character():
    a = "# 标题\n\n执行 `kubectl get pods`\n"
    b = "# 标题\n\n执行 `kubectl get pod`\n"
    assert dd.markdown_doc_digest(a) != dd.markdown_doc_digest(b)


def test_markdown_digest_keeps_inline_trailing_spaces():
    """行尾两空格是 markdown 换行语义，不能被规范化吃掉。"""
    assert dd.markdown_doc_digest("第一行  \n第二行") != dd.markdown_doc_digest("第一行\n第二行")


def test_openapi_digest_is_key_order_insensitive():
    a = {"info": {"title": "T"}, "paths": {"/a": {"get": {"summary": "S"}}}}
    b = {"paths": {"/a": {"get": {"summary": "S"}}}, "info": {"title": "T"}}
    assert dd.openapi_doc_digest(a) == dd.openapi_doc_digest(b)


def test_openapi_digest_changes_with_operation_text():
    a = {"paths": {"/a": {"get": {"summary": "S"}}}}
    b = {"paths": {"/a": {"get": {"summary": "S2"}}}}
    assert dd.openapi_doc_digest(a) != dd.openapi_doc_digest(b)


def test_reviewed_at_missing_and_blank_are_none():
    assert dd.parse_reviewed_at({}, where="rb-001.md") is None
    assert dd.parse_reviewed_at({"reviewed_at": ""}, where="rb-001.md") is None
    assert dd.parse_reviewed_at({"reviewed_at": "   "}, where="rb-001.md") is None


def test_reviewed_at_valid_date_is_returned_as_is():
    assert dd.parse_reviewed_at({"reviewed_at": "2026-09-16"}, where="rb-001.md") == "2026-09-16"


@pytest.mark.parametrize("bad", ["2026-9-6", "2026/09/16", "16-09-2026", "2026-02-30", "yesterday"])
def test_reviewed_at_invalid_raises_with_file_name(bad: str):
    with pytest.raises(ValueError) as exc:
        dd.parse_reviewed_at({"reviewed_at": bad}, where="rb-208-dependency-down.md")
    assert "rb-208-dependency-down.md" in str(exc.value)
    assert "reviewed_at" in str(exc.value)


def test_reviewed_at_list_is_rejected():
    with pytest.raises(ValueError, match="不支持列表"):
        dd.parse_reviewed_at({"reviewed_at": ["2026-09-16"]}, where="pm-001.md")
