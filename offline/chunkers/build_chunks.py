"""切分入口：扫描 corpus，产出 chunks.jsonl（进 git 的离线产物）。

用法: python chunkers/build_chunks.py [--corpus offline/corpus] [--out offline/corpus/chunks.jsonl]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import openapi_ast_chunker as oa  # noqa: E402
import markdown_tree_chunker as md  # noqa: E402
from doc_digest import is_digest  # noqa: E402


def build(corpus_dir: Path) -> list[dict]:
    chunks: list[dict] = []
    # doc_id 是引用/评测/引用标注的文档身份，两个文件自称同一篇会让"参考[1] rb-101"
    # 无法区分是哪篇、golden 的 expected_docs 语义含混（实测：旧 1xx 系列与新本机辖区
    # 系列撞号，两份内容混在同一个 doc_id 下入库才发现）。chunk_id 有内容哈希兜底不会
    # 撞，所以这里的撞号是静默的——必须在产线入口按"文件 → doc_id 声明"比对拦截。
    owner: dict[str, str] = {}
    dupes: list[str] = []

    def claim(file_label: str, cs: list[dict]) -> None:
        chunks.extend(cs)
        for d in {c["doc_id"] for c in cs}:
            prev = owner.setdefault(d, file_label)
            if prev != file_label and d not in dupes:
                dupes.append(d)

    for j in sorted((corpus_dir / "openapi").glob("*.json")):
        claim(f"openapi/{j.name}", oa.chunk_file(str(j), doc_id=j.stem))
    for sub in ("postmortems", "runbooks"):
        for m in sorted((corpus_dir / sub).glob("*.md")):
            claim(f"{sub}/{m.name}", md.chunk_file(str(m)))
    if dupes:
        # 显式 raise 而非 assert：python -O 会剥掉 assert，产线闸门不能建在会被优化掉的原语上
        raise ValueError(f"doc_id 撞号（多个文件自称同一篇）: {dupes}")
    _assert_doc_digests(chunks)
    return chunks


def _assert_doc_digests(chunks: list[dict]) -> None:
    """同一文档的所有 chunk 必须共享同一版本身份；摘要形状必须可校验。

    这是 S3 的构建期构造保证：如果摘要按 chunk 各算一份，同一篇文档会出现多个"版本"，
    引用面板上同一份 runbook 的两条依据长得像两个版本。
    """
    by_doc: dict[str, str] = {}
    for c in chunks:
        dg = c["metadata"].get("doc_digest")
        if not is_digest(dg):
            raise ValueError(f"chunk 缺少合法 doc_digest（12 位小写 hex）: {c['chunk_id']} -> {dg!r}")
        prev = by_doc.setdefault(c["doc_id"], dg)
        if prev != dg:
            raise ValueError(
                f"同一文档出现多个 doc_digest: {c['doc_id']} {prev} != {dg}（chunk {c['chunk_id']}）")


def _contained(p: Path, root: Path, label: str) -> Path:
    """解析路径并强制落在 offline/ 根内，拒绝 .. 越界与任意文件写。"""
    rp = p.resolve()
    if root not in rp.parents and rp != root:
        raise ValueError(f"{label} 路径越出允许目录 {root}: {p}")
    return rp


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default=str(Path(__file__).parent.parent / "corpus"))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    offline_root = Path(__file__).parent.parent.resolve()
    corpus = _contained(Path(args.corpus), offline_root, "corpus")
    chunks = build(corpus)

    ids = [c["chunk_id"] for c in chunks]
    repeated = sorted({i for i in ids if ids.count(i) > 1})
    if repeated:
        raise ValueError(f"chunk_id 重复: {repeated}")

    out = _contained(Path(args.out), offline_root, "out") if args.out else corpus / "chunks.jsonl"
    # newline="\n"：Windows 文本模式会把 \n 翻译成 \r\n，产物摘要跨平台假红（CI 2026-10-06 实测）
    with out.open("w", encoding="utf-8", newline="\n") as f:
        for c in chunks:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    by_type: dict[str, int] = {}
    for c in chunks:
        by_type[c["type"]] = by_type.get(c["type"], 0) + 1
    print(f"OK chunks={len(chunks)} -> {out}")
    print(f"by_type={by_type}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
