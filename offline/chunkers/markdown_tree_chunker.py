"""Markdown 标题树感知切分器。

- 解析 YAML front matter 提取 service / env / auth_level / error_codes 元数据
- 维护 H1~H3 面包屑标题路径，写入 Chunk 头部与元数据
- 严密保护 ``` / ~~~ 代码块：块内标题与围栏不被误判，跨块不腰斩
"""
from __future__ import annotations

import re
from pathlib import Path

from errorcode import ERROR_CODE_RE  # 与在线 Java 词法同源，见 errorcode.py
from defaults import DEFAULT_TENANT
from doc_digest import markdown_doc_digest, parse_reviewed_at

FENCE_RE = re.compile(r"^\s*(```|~~~)")
HEADING_RE = re.compile(r"^(#{1,3})\s+(.+?)\s*$")
FRONT_MATTER_RE = re.compile(r"\A---\s*\n(.*?)\n---\s*\n", re.DOTALL)


def parse_front_matter(raw: str) -> tuple[dict, str]:
    """极简 YAML 解析（仅支持 key: value 与 [a, b] 列表），返回 (meta, body)。"""
    m = FRONT_MATTER_RE.match(raw)
    if not m:
        return {}, raw
    meta: dict = {}
    for line in m.group(1).splitlines():
        if ":" not in line or line.lstrip().startswith("#"):
            continue
        key, _, val = line.partition(":")
        key, val = key.strip(), val.strip()
        if val.startswith("[") and val.endswith("]"):
            meta[key] = [v.strip() for v in val[1:-1].split(",") if v.strip()]
        else:
            meta[key] = val
    return meta, raw[m.end():]


def _split_sections(body: str) -> list[dict]:
    """按 H1~H3 切分，代码块内的 # 不触发切分。"""
    sections: list[dict] = []
    current: dict | None = None
    in_fence = False
    for line in body.splitlines():
        if FENCE_RE.match(line):
            in_fence = not in_fence
        hm = None if in_fence else HEADING_RE.match(line)
        if hm:
            level = len(hm.group(1))
            title = hm.group(2)
            current = {"level": level, "title": title, "lines": [line]}
            sections.append(current)
        elif current is not None:
            current["lines"].append(line)
        elif line.strip():
            # 首个标题前的正文（理论上 H1 在最前，此处兜底）
            current = {"level": 1, "title": "", "lines": [line]}
            sections.append(current)
    return sections


def chunk_markdown(raw: str, doc_id: str, *, where: str | None = None) -> list[dict]:
    meta, body = parse_front_matter(raw)
    sections = _split_sections(body)
    # 版本身份按"正文"现算，front matter 的变化（如签名 reviewed_at）不制造版本跳变。
    doc_digest = markdown_doc_digest(body)
    reviewed_at = parse_reviewed_at(meta, where=where or doc_id)

    # 面包屑栈：breadcrumb[level] = title
    crumbs: dict[int, str] = {}
    chunks: list[dict] = []
    for sec in sections:
        level, title = sec["level"], sec["title"]
        if title:
            crumbs[level] = title
            for deeper in [k for k in crumbs if k > level]:
                crumbs.pop(deeper)
        path_titles = [crumbs[l] for l in sorted(crumbs) if l <= level]
        breadcrumb = " > ".join(path_titles) if path_titles else doc_id
        text = "\n".join(sec["lines"]).strip()
        if not text:
            continue
        error_codes = sorted(set(ERROR_CODE_RE.findall(text)) | set(meta.get("error_codes", [])))
        chunks.append(
            {
                "chunk_id": f"{doc_id}::{breadcrumb}",
                "doc_id": doc_id,
                "type": "markdown_section",
                "text": f"[{breadcrumb}]\n{text}",
                "breadcrumb": breadcrumb,
                "metadata": {
                    "service": meta.get("service", "unknown"),
                    "endpoint": None,
                    "method": None,
                    "error_codes": error_codes,
                    "auth_level": int(meta.get("auth_level", 1)),
                    "env": meta.get("env", "prod"),
                    "tenant": str(meta.get("tenant") or DEFAULT_TENANT),  # front matter 可覆盖
                    "doc_digest": doc_digest,
                    "reviewed_at": reviewed_at,
                },
            }
        )
    return chunks


def chunk_file(path: str) -> list[dict]:
    p = Path(path)
    # doc_id 取形如 pm-001 / rb-001 的前缀
    m = re.match(r"([a-z]+-\d+)", p.stem)
    doc_id = m.group(1) if m else p.stem
    return chunk_markdown(p.read_text(encoding="utf-8"), doc_id=doc_id, where=p.name)
