"""文档版本身份与审查时效的离线判据（S3 引用溯源）。

为什么版本用内容摘要而不是手填版本号：
- 手填版本号会漂移（改正文忘改号、两篇文档共用号），而摘要由字节现算，改一个字符必然变；
- 同代门闩（`offline/provenance.py`）已经用同一思路判定"报告是否仍是当前语料的报告"。

为什么摘要只覆盖正文而不是整份文件：
- front matter 的 `reviewed_at` 变化不应制造"正文版本"跳变；审查日期走独立字段、
  独立判定，引用面板不该因为一次签字就显示成"新版本"。

只用 stdlib，不触网。
"""
from __future__ import annotations

import hashlib
import json
import re
from datetime import date

DIGEST_HEX_LEN = 12
_DIGEST_RE = re.compile(r"^[0-9a-f]{12}$")
_REVIEWED_AT_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def is_digest(value: object) -> bool:
    """12 位小写 hex——构建期与消费侧共用的唯一形状判据。"""
    return isinstance(value, str) and bool(_DIGEST_RE.match(value))


def normalize_markdown_body(body: str) -> str:
    """CRLF/CR → LF，去首尾换行；不动行内空白（行尾两空格是 markdown 换行语义）。"""
    return body.replace("\r\n", "\n").replace("\r", "\n").strip("\n")


def digest_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:DIGEST_HEX_LEN]


def markdown_doc_digest(body: str) -> str:
    return digest_text(normalize_markdown_body(body))


def canonical_openapi_json(spec: dict) -> str:
    """键序无关的规范形态：语义相同的 spec 必须得到同一摘要。"""
    return json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def openapi_doc_digest(spec: dict) -> str:
    return digest_text(canonical_openapi_json(spec))


def parse_reviewed_at(meta: dict, *, where: str) -> str | None:
    """严格解析 `reviewed_at`：缺失 → None；非法 → ValueError（点名文件与字段）。

    刻意不读系统时钟：未来日期在构建期是合法字符串，放到运行期判 `unknown`，
    否则同一份语料在不同日期重建会产出不同结果。
    """
    raw = meta.get("reviewed_at")
    if raw is None:
        return None
    if isinstance(raw, list):
        raise ValueError(f"{where}: reviewed_at 必须是单个 YYYY-MM-DD，不支持列表")
    value = str(raw).strip()
    if not value:
        return None
    if not _REVIEWED_AT_RE.match(value):
        raise ValueError(f"{where}: reviewed_at 格式必须是 YYYY-MM-DD，实际为 {value!r}")
    try:
        date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError(f"{where}: reviewed_at 不是有效日期: {value!r}") from exc
    return value
