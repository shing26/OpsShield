"""OpenAPI 3.0 AST 结构化切分器。

以单个 API Endpoint（path + method）为原子粒度产出自包含语义块，
提取 method / path / error_codes / service 为元数据，供 ES keyword
精确符号检索使用。纯标准库实现，无外部依赖。
"""
from __future__ import annotations

import json
import re

from errorcode import ERROR_CODE_RE  # 与在线 Java 词法同源，见 errorcode.py
from defaults import DEFAULT_TENANT
from doc_digest import openapi_doc_digest

HTTP_METHODS = ("get", "post", "put", "delete", "patch", "head")


def render_endpoint(title: str, path: str, method: str, op: dict) -> str:
    """将单个 endpoint 渲染为自包含文本块。"""
    lines = [
        f"API Endpoint: {method.upper()} {path}",
        f"所属服务: {(op.get('tags') or ['unknown'])[0]}",
        f"摘要: {op.get('summary', '')}",
    ]
    if op.get("description"):
        lines.append(f"说明: {op['description']}")
    for p in op.get("parameters", []):
        schema = p.get("schema", {}) or {}
        lines.append(
            f"参数: {p.get('name')} (位置={p.get('in')}, 类型={schema.get('type', 'object')})"
        )
    rb = op.get("requestBody")
    if rb:
        lines.append(f"请求体: {json.dumps(rb, ensure_ascii=False)}")
    for code, resp in sorted((op.get("responses") or {}).items()):
        lines.append(f"响应 {code}: {resp.get('description', '')}")
    return "\n".join(lines)


def chunk_openapi(spec: dict, doc_id: str = "openapi-order-center") -> list[dict]:
    """解析 OpenAPI spec，每个 endpoint 产出一个 chunk。"""
    info_title = (spec.get("info") or {}).get("title", "OpenAPI")
    doc_digest = openapi_doc_digest(spec)  # 键序无关：同一语义 spec 摘要恒定
    chunks: list[dict] = []
    for path, item in (spec.get("paths") or {}).items():
        for method, op in item.items():
            if method.lower() not in HTTP_METHODS or not isinstance(op, dict):
                continue
            method = method.lower()
            service = (op.get("tags") or ["unknown"])[0]
            op_json = json.dumps(op, ensure_ascii=False)
            error_codes = sorted(set(ERROR_CODE_RE.findall(op_json)))
            breadcrumb = f"{info_title} > {service} > {method.upper()} {path}"
            body = render_endpoint(info_title, path, method, op)
            chunks.append(
                {
                    "chunk_id": f"{doc_id}::{method.upper()} {path}",
                    "doc_id": doc_id,
                    "type": "api_endpoint",
                    "text": f"[{breadcrumb}]\n{body}",
                    "breadcrumb": breadcrumb,
                    "metadata": {
                        "service": service,
                        "endpoint": path,
                        "method": method.upper(),
                        "error_codes": error_codes,
                        "auth_level": 1,
                        "env": "prod",
                        "tenant": DEFAULT_TENANT,
                        "doc_digest": doc_digest,
                        "reviewed_at": None,
                    },
                }
            )
    return chunks


def chunk_file(path: str, doc_id: str) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        spec = json.load(f)
    return chunk_openapi(spec, doc_id=doc_id)
