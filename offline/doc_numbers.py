"""文档数字契约——把"README 里的数字"钉在产物上（同代门闩的**面二**）。

事故史（同一类错误两次，两次都靠人发现）：
  台账 E1（`docs/qa/2026-09-13-module-verification.md`）：评测报告系语料扩充前的版本，
  `es_only` 语义 Top-1 实测 72%→64%，而 README 引用的仍是旧数字；2026-09-16 又复现一次
  （303→423 后 Hit@3 100%→92%）。`offline/provenance.py` 是为此立的门闩，但它钉的是
  **面一：报告↔语料同代**（比内容摘要）。面一同代并不等于面二同代——报告是对的，
  人把它抄进 README 时抄错、或改了产物忘了改文档，面一完全看不见。
  本模块是**面二：文档里的数字必须等于现在从产物算出来的数字**。

事故史第三例（本模块立项时的实测）：README 写「121 用例」，而 `src/test` 下 `@Test`
声明已是 131 条——差的 10 条正是两次修复新增的用例。同一缺陷类在面一上线后**又发生了一次**，
只是没人看见。这就是"面一不够"的实证。

三个刻意的设计决定：

1. **不设 `--stamp`、不存数值快照**。登记表只记"这个数字在哪、怎么从产物派生"，判据每次现算。
   provenance 的 `--stamp` 是必要的（报告的口径/后端/时间戳本身是产物的一部分），
   但文档数字没有这个需要：一旦允许"重新登记"，漂移就会被登记动作顺手掩盖——
   那正是本项目反复警惕的"门闩被自己关掉"的失效方式。

2. **每个 `cited_in` 至少命中一次**。0 命中说明门闩**指不到人**（文案被改写、数字被搬走），
   必须报红而不是静默放过。"悄悄失效的门闩"比"没有门闩"更危险：它让人以为这里有保护。

3. **命中的全部必须等于派生值**。同一数字在文档里常出现多次（正文 + 目录表 + 其它文档），
   只校验第一处等于给其余处开后门。故同一 pattern 的多处命中逐处比对，任一处漂移即红。

只登记**可从库内产物确定性派生**的数字。live 实测时长（"重建 22.3s"）与并发实测值含波动、
依赖活体栈，纳入就会假红——而会假红的门闩比没有门闩更快被关掉（同款理由见 `provenance.py` 顶部）。
这类数字仍是人工纪律，是**已知边界**：本模块如实不覆盖，而不是假装覆盖。

只用 stdlib，不触网；`--check` 可在 CI 零凭据运行。
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REGISTRY_REL = "offline/doc_numbers.json"

FORMATS = ("int", "percent", "decimal1", "decimal3")


class RegistryError(Exception):
    """登记表或产物本身的问题（区别于"文档数字与产物不一致"）。"""


def _lines(path: Path) -> list[str]:
    """按行读取；`open` 的通用换行会把 CRLF 归一，故行内匹配与检出平台无关。"""
    try:
        return path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise RegistryError(f"{path} 读取失败：{exc}")


def _files_under(root: Path, globs: list[str]) -> list[Path]:
    out: list[Path] = []
    for pat in globs:
        out.extend(p for p in root.glob(pat) if p.is_file())
    return sorted(set(out))


def _cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def _json_field(root: Path, rel: str, dotted: str):
    try:
        cur = json.loads((root / rel).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RegistryError(f"{rel} 读取失败：{exc}")
    for part in dotted.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        elif isinstance(cur, list) and part.isdigit() and int(part) < len(cur):
            cur = cur[int(part)]
        else:
            raise RegistryError(f"{rel} 里不存在字段 {dotted!r}（断在 {part!r}）")
    return cur


def _csv_field(root: Path, rel: str, where: dict, column: str) -> float:
    try:
        with (root / rel).open(encoding="utf-8", newline="") as fh:
            rows = list(csv.DictReader(fh))
    except OSError as exc:
        raise RegistryError(f"{rel} 读取失败：{exc}")
    if not rows:
        raise RegistryError(f"{rel} 无数据行")
    if column not in rows[0]:
        raise RegistryError(f"{rel} 无列 {column!r}（现有：{sorted(rows[0])}）")
    for row in rows:
        if all(str(row.get(k, "")) == str(v) for k, v in where.items()):
            try:
                return float(row[column])
            except (TypeError, ValueError):
                raise RegistryError(f"{rel} 的 {column!r} 不是数值：{row[column]!r}")
    raise RegistryError(f"{rel} 找不到满足 {where} 的行")


def _md_table_row(root: Path, rel: str, column: str, row_contains: str) -> float:
    """取 markdown 表里某行某列的值（表头按列名定位，避免列顺序变化导致错读）。"""
    header_seen = False
    col_idx: int | None = None
    for line in _lines(root / rel):
        if not line.lstrip().startswith("|"):
            continue
        cells = _cells(line)
        if not header_seen and column in cells:
            header_seen, col_idx = True, cells.index(column)
            continue
        if header_seen and any(row_contains in c for c in cells):
            raw = cells[col_idx].replace("*", "").strip() if col_idx is not None else ""
            try:
                return float(raw)
            except ValueError:
                raise RegistryError(f"{rel} 的 {column!r} 列不是数值：{raw!r}")
    raise RegistryError(f"{rel} 找不到表头列 {column!r} 或含 {row_contains!r} 的行")


def derive(spec: dict, root: Path):
    """从产物/源码现算一个数字——判据的来源，不是登记时刻的快照。"""
    kind = spec.get("kind")
    if kind == "grep_count":
        rx = re.compile(spec["pattern"])
        return sum(1 for f in _files_under(root, spec["globs"]) for ln in _lines(f) if rx.search(ln))
    if kind == "file_count":
        files = _files_under(root, spec["globs"])
        if "suffix" in spec:
            files = [f for f in files if f.suffix == spec["suffix"]]
        return len(files)
    if kind == "jsonl_lines":
        return sum(1 for ln in _lines(root / spec["path"]) if ln.strip())
    if kind == "jsonl_count_where":
        n = 0
        for ln in _lines(root / spec["path"]):
            if not ln.strip():
                continue
            try:
                row = json.loads(ln)
            except json.JSONDecodeError as exc:
                raise RegistryError(f"{spec['path']} 有非法 JSON 行：{exc}")
            if all(row.get(k) == v for k, v in spec["equals"].items()):
                n += 1
        return n
    if kind == "json_field":
        return _json_field(root, spec["path"], spec["field"])
    if kind == "csv_field":
        return _csv_field(root, spec["path"], spec["where"], spec["column"])
    if kind == "md_table_row":
        return _md_table_row(root, spec["path"], spec["column"], spec["row_contains"])
    # 自指（#39）：门闩自身的规模也是文档里的数字——登记表条数与引用处数同样会漂移，
    # 而它们是唯一能校验“门闩有没有被悄悄关掉”的读数。
    if kind == "registry_entries":
        return len(load_registry(root))
    if kind == "registry_citations":
        return sum(len(e["cited_in"]) for e in load_registry(root))
    raise RegistryError(f"未知 kind：{kind!r}")


def format_value(value, fmt: str) -> str:
    """把派生值渲染成文档里该出现的**字面量**（比对的是字面量，故格式是契约的一部分）。"""
    try:
        num = float(value)
    except (TypeError, ValueError):
        raise RegistryError(f"派生值不是数值：{value!r}")
    if fmt == "int":
        return f"{round(num):d}"
    if fmt == "percent":
        pct = num * 100
        return f"{pct:.0f}" if abs(pct - round(pct)) < 1e-9 else f"{pct:.1f}"
    if fmt == "decimal1":
        return f"{num:.1f}"
    if fmt == "decimal3":
        return f"{num:.3f}"
    raise RegistryError(f"未知 format：{fmt!r}（可用：{FORMATS}）")


def _compile(pattern: str, group: int) -> re.Pattern:
    try:
        rx = re.compile(pattern)
    except re.error as exc:
        raise RegistryError(f"非法正则 {pattern!r}：{exc}")
    if rx.groups < group:
        raise RegistryError(f"pattern {pattern!r} 只有 {rx.groups} 个捕获组，取不到第 {group} 组")
    return rx


def citations(root: Path, cite: dict) -> list[tuple[int, str]]:
    """返回 [(行号, 命中字面量)]——**0 命中是一个必须报红的结论**，不是"跳过"。"""
    group = int(cite.get("group", 1))
    rx = _compile(cite["pattern"], group)
    hits: list[tuple[int, str]] = []
    for lineno, line in enumerate(_lines(root / cite["file"]), start=1):
        for m in rx.finditer(line):
            hits.append((lineno, m.group(group).strip()))
    return hits


def load_registry(root: Path = ROOT) -> list[dict]:
    try:
        data = json.loads((root / REGISTRY_REL).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RegistryError(f"{REGISTRY_REL} 无法读取：{exc}")
    entries = data.get("entries")
    if not isinstance(entries, list) or not entries:
        raise RegistryError(f"{REGISTRY_REL} 缺非空 entries 列表")
    seen: set[str] = set()
    for e in entries:
        for key in ("id", "unit", "derive", "format", "cited_in"):
            if key not in e:
                raise RegistryError(f"登记项缺 {key!r}：{e.get('id', e)}")
        if e["id"] in seen:
            raise RegistryError(f"登记项 id 重复：{e['id']}")
        seen.add(e["id"])
        if e["format"] not in FORMATS:
            raise RegistryError(f"{e['id']} 的 format 非法：{e['format']!r}")
        if not e["cited_in"]:
            raise RegistryError(f"{e['id']} 的 cited_in 为空——登记了数字却不说它在哪，等于没登记")
        for cite in e["cited_in"]:
            _compile(cite["pattern"], int(cite.get("group", 1)))
    return entries


def check(root: Path = ROOT) -> int:
    """CI 门闩：文档里的每个登记数字都必须等于现在从产物算出来的数字。"""
    try:
        entries = load_registry(root)
    except RegistryError as exc:
        print(f"FAIL 登记表不可用：{exc}", file=sys.stderr)
        return 1

    fails: list[str] = []
    citations_checked = 0
    for e in entries:
        eid = e["id"]
        try:
            want = format_value(derive(e["derive"], root), e["format"])
        except RegistryError as exc:
            fails.append(f"{eid}: 派生失败——{exc}（产物缺失，或登记表写错）")
            continue
        for cite in e["cited_in"]:
            try:
                hits = citations(root, cite)
            except RegistryError as exc:
                fails.append(f"{eid}: 登记项不可用——{exc}")
                continue
            if not hits:
                fails.append(f"{eid}: `{cite['file']}` 里 pattern **0 命中**——门闩指不到人"
                             f"（文案被改写？数字被搬走？）。期望值 {want}，pattern: {cite['pattern']}")
                continue
            citations_checked += 1
            for lineno, got in hits:
                if got != want:
                    # 措辞刻意写「真值 / 文档写的是」而不是「期望 / 实际」：后者的主语有歧义，
                    # 本模块作者自己就把它读反过一次（以为"期望"是文档该写的值），
                    # 而读反的方向恰好会导致人去改产物、而不是改文档——最坏的一种误导。
                    fails.append(f"{eid}: `{cite['file']}:{lineno}` 真值 {want}、文档写的是 {got}"
                                 f"（{e['unit']}）")

    if fails:
        print("FAIL 文档数字与产物不一致——README/文档引用的数字已陈旧（台账 E1 教训，面二）。",
              file=sys.stderr)
        for line in fails:
            print(f"     {line}", file=sys.stderr)
        print("     修法：按产物的真值改文档（`python doc_numbers.py --show` 打印全部派生值）；",
              file=sys.stderr)
        print("     若文案改了措辞导致 pattern 指不到人，同批更新 offline/doc_numbers.json 的 pattern。",
              file=sys.stderr)
        return 1

    print(f"OK   {len(entries)} 条文档数字与产物一致（{citations_checked} 处引用全部命中且相符）")
    print(f"     判据：每次从产物现算，不存快照——不存在「重新登记即掩盖漂移」的失效面。")
    return 0


def show(root: Path = ROOT) -> int:
    """打印每条数字的派生值与其全部命中位置（改文档时照这个改）。"""
    try:
        entries = load_registry(root)
    except RegistryError as exc:
        print(f"FAIL 登记表不可用：{exc}", file=sys.stderr)
        return 1
    for e in entries:
        try:
            want = format_value(derive(e["derive"], root), e["format"])
        except RegistryError as exc:
            print(f"{e['id']:<34} 派生失败：{exc}")
            continue
        print(f"{e['id']:<34} = {want:<8} ({e['unit']})")
        for cite in e["cited_in"]:
            try:
                hits = citations(root, cite)
            except RegistryError as exc:
                print(f"     ! {cite['file']}: 登记项不可用——{exc}")
                continue
            if not hits:
                print(f"     ! {cite['file']}: pattern 0 命中（门闩指不到人）")
            for lineno, got in hits:
                mark = "ok " if got == want else "MISMATCH"
                print(f"     {mark} {cite['file']}:{lineno} = {got}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="文档数字契约：文档里的数字必须等于产物算出来的数字")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--check", action="store_true", help="校验（CI 用；不一致返回 1）")
    g.add_argument("--show", action="store_true", help="打印派生值与全部命中位置（不判定）")
    a = ap.parse_args(argv)
    return show() if a.show else check()


if __name__ == "__main__":
    raise SystemExit(main())
