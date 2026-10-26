"""文档数字契约的回归锁（`offline/doc_numbers.py` + `doc_numbers.json`）。

为什么这些用例必须存在：面二门闩有两类**静默失效**，两类都不会报错，只会让人以为这里有保护：

  1. **指不到人**——文案改了措辞，pattern 再也匹配不上，门闩从此空转却仍然"绿"。
     这比没有门闩更危险（本项目对空转门闩已有前科：CI 里用 mtime 判同代，`git checkout`
     刷平时间戳后判据恒真）。
  2. **指错人**——判据没绑在产物上（比如改成"文档自己前后一致"），于是产物变了而门闩不响。

故本文件既锁"改坏必红"（变异验证），也锁"反向不得红"（正常流程 + 刻意不覆盖的边界）。

变异一律在**仓库镜像**上做：`_mirror()` 按登记表自身列出的文件复制出一份 tmp 仓库，
于是仓库本体永不被测试改脏，而镜像的保真度足以让真实登记表完整跑一遍。
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

OFFLINE = Path(__file__).resolve().parent.parent
REPO = OFFLINE.parent

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把某条不变量改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
sys.path.insert(0, str(OFFLINE))
import doc_numbers as dn  # noqa: E402


# ---------------------------------------------------------------- 镜像与工具

def _needed_rels(root: Path) -> list[str]:
    """登记表自己说它需要哪些文件（登记表本体 + globs / path / cited_in），据此复制镜像。"""
    rels: set[str] = {dn.REGISTRY_REL}
    for e in dn.load_registry(root):
        d = e["derive"]
        for g in d.get("globs", []):
            rels |= {p.relative_to(root).as_posix() for p in root.glob(g) if p.is_file()}
        if "path" in d:
            rels.add(d["path"])
        for c in e["cited_in"]:
            rels.add(c["file"])
    return sorted(rels)


def _mirror(tmp_path: Path) -> Path:
    """把门闩依赖的文件集复制成一份可随意改坏的仓库镜像。"""
    root = tmp_path / "repo"
    for rel in _needed_rels(REPO):
        src, dst = REPO / rel, root / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(src.read_bytes())
    return root


def _is_gitignored(rel: str) -> bool:
    r = subprocess.run(["git", "check-ignore", "-q", rel], cwd=REPO, check=False, capture_output=True)
    return r.returncode == 0


# ---------------------------------------------------------------- 正向：真实仓库

def test_real_repo_is_green() -> None:
    """正常流程不得红——会假红的门闩比没有门闩更快被关掉。"""
    assert dn.check(REPO) == 0


def test_real_repo_is_green_twice() -> None:
    """连跑两次同结果：判据不得依赖顺序、时间戳或运行残留。"""
    assert (dn.check(REPO), dn.check(REPO)) == (0, 0)


def test_every_registered_number_points_at_someone() -> None:
    """每条 cited_in 在真实文档里都必须 ≥1 命中——防"指不到人"的静默腐化。"""
    dead: list[str] = []
    for e in dn.load_registry(REPO):
        for cite in e["cited_in"]:
            if not dn.citations(REPO, cite):
                dead.append(f"{e['id']} → {cite['file']} / {cite['pattern']}")
    assert not dead, f"这些登记项指不到人（文案已改而登记表没跟上）：{dead}"


def test_registry_paths_are_supplyable_by_a_clean_checkout() -> None:
    """登记表引用的文件必须入库（不得是 gitignore 路径），否则干净检出下门闩空转。"""
    offenders = [rel for rel in _needed_rels(REPO) if _is_gitignored(rel)]
    assert not offenders, f"这些路径被 gitignore，干净检出下不存在：{offenders}"


def test_registry_has_no_duplicate_ids_and_every_entry_is_verifiable() -> None:
    """登记表自身不变量：id 唯一、format 合法、cited_in 非空、pattern 捕获组够用。"""
    entries = dn.load_registry(REPO)
    assert len({e["id"] for e in entries}) == len(entries)
    for e in entries:
        assert e["format"] in dn.FORMATS
        assert e["cited_in"], f"{e['id']} 没说它在哪出现"
        for cite in e["cited_in"]:
            dn._compile(cite["pattern"], int(cite.get("group", 1)))


# ---------------------------------------------------------------- 变异 A/B/C

def _derived(root: Path, entry_id: str) -> str:
    """从产物现算登记项的真值——**测试自己也不许硬编码数字**。

    踩过（2026-09-23）：本文件最初把 131 写死，随后 `@Test` 数因新增用例涨到 139，
    变异注入再也命中不了文档，用例**假红**——而门闩本身是绿的、判据没坏。
    "会假红的门闩比没有门闩更快被关掉"，故这里与被测门闩共用同一个派生入口。
    """
    entry = next(e for e in dn.load_registry(root) if e["id"] == entry_id)
    return dn.format_value(dn.derive(entry["derive"], root), entry["format"])


def test_mutation_a_doc_number_changed_turns_gate_red(tmp_path: Path) -> None:
    """变异 A（文档侧）：按**登记表自己的引用位**把该文档的用例数改大 1 → 必须红，且点名文件:行。

    为什么不硬编码引用措辞（2026-09-27 实测教训）：本用例最初写死 `f"{val} 用例"`，而本轮把该措辞
    随「目录与文档地图」搬去了 `docs/repo-map.md`——用例当场**假红**，而门闩本身是绿的、判据没坏。
    根因是把"文案写在哪个文件哪句话里"当成了不变量；真正的不变量是 registry 的 `cited_in`。
    改为从中取第一个引用位（含 file/pattern/group），文案搬家 + 登记表同批更新时用例自动跟上。
    """
    root = _mirror(tmp_path)
    entry = next(e for e in dn.load_registry(root) if e["id"] == "java_test_declarations")
    cite = next(c for c in entry["cited_in"] if c["file"] == "README.md")
    val = _derived(root, "java_test_declarations")

    hits = dn.citations(root, cite)              # [(行号, 命中字面量)]
    assert hits, f"{cite['file']} 未按登记 pattern {cite['pattern']!r} 引用——那是另一类红，见其它用例"
    lineno, literal = hits[0]
    assert literal == val, f"文档写的是 {literal}、真值 {val}——那是另一类红，见其它用例"

    wrong = str(int(val) + 1)
    path = root / cite["file"]
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    lines[lineno - 1] = lines[lineno - 1].replace(literal, wrong, 1)
    path.write_text("".join(lines), encoding="utf-8")

    assert dn.check(root) == 1
    out = _capture_check(root)
    # 行号现算而不是硬编码：文档增删一行就换行号，硬编码会把"断言过时"伪装成"门闩坏了"。
    assert f"{cite['file']}:{lineno}" in out, out
    assert f"真值 {val}、文档写的是 {wrong}" in out, out


def test_mutation_b_artifact_changed_turns_gate_red(tmp_path: Path) -> None:
    """变异 B（产物侧）：给 chunks.jsonl 加一行 → 必须红，证明判据绑的是产物。"""
    root = _mirror(tmp_path)
    before = _derived(root, "chunks_lines")
    chunks = root / "offline/corpus/chunks.jsonl"
    chunks.write_bytes(chunks.read_bytes() + b'{"id":"x","text":"y"}\n')

    assert dn.check(root) == 1
    out = _capture_check(root)
    assert "chunks_lines" in out, out
    # 产物加了一行 → 真值 = 原值+1，而文档仍写着原值
    assert f"真值 {int(before) + 1}、文档写的是 {before}" in out, out


def test_mutation_c_rewritten_wording_is_reported_not_skipped(tmp_path: Path) -> None:
    """变异 C（指不到人）：把「N 单测」改成「N 个单测」→ 必须红并报 0 命中。

    这是面二最容易退化成空转的路径：数字其实还在，只是门闩再也看不见它。
    """
    root = _mirror(tmp_path)
    readme = root / "README.md"
    val = _derived(root, "java_test_declarations")
    readme.write_text(readme.read_text(encoding="utf-8").replace(f"{val} 单测", f"{val} 个单测"),
                      encoding="utf-8")

    assert dn.check(root) == 1
    out = _capture_check(root)
    assert "0 命中" in out, out
    assert "java_test_declarations" in out, out


def test_mutation_d_missing_artifact_reports_derive_failure(tmp_path: Path) -> None:
    """变异 D：产物缺失 → 报"派生失败"，而不是静默跳过该条。"""
    root = _mirror(tmp_path)
    (root / "offline/eval/reports/eval_report.json").unlink()

    assert dn.check(root) == 1
    assert "派生失败" in _capture_check(root)


# ---------------------------------------------------------------- 反向：刻意的边界

def test_unregistered_numbers_may_change_without_red(tmp_path: Path) -> None:
    """反向边界：**未登记**的数字改了不得红。

    刻意不覆盖 live 实测时长（如"423 文档 22.3s"）与需活体栈的读数——它们含波动，
    纳入就会假红。本用例把这条边界钉住：门闩只对它登记过的数字负责，不做全文扫描。
    """
    root = _mirror(tmp_path)
    readme = root / "README.md"
    text = readme.read_text(encoding="utf-8")
    assert "22.3s" in text
    readme.write_text(text.replace("22.3s", "9.9s"), encoding="utf-8")

    assert dn.check(root) == 0


def test_historical_narrative_numbers_are_not_gated(tmp_path: Path) -> None:
    """反向边界：历史叙事口径（如"115→118 全绿"、"0.813→0.767"）不参与判定。

    它们是"当时的实测"，改历史去迎合现量才是错的。
    """
    root = _mirror(tmp_path)
    readme = root / "README.md"
    text = readme.read_text(encoding="utf-8")
    assert "0.813→0.767" in text
    readme.write_text(text.replace("0.813→0.767", "0.814→0.768"), encoding="utf-8")

    assert dn.check(root) == 0


# ---------------------------------------------------------------- 派生/格式的纯函数

def test_derive_kinds_on_synthetic_fixture(tmp_path: Path) -> None:
    root = tmp_path / "r"
    (root / "src/test/a").mkdir(parents=True)
    (root / "src/test/a/T.java").write_text("@Test\n  @Test\n// @Test in comment\n", encoding="utf-8")
    (root / "docs/adr").mkdir(parents=True)
    (root / "docs/adr/0001-a.md").write_text("x", encoding="utf-8")
    (root / "d.jsonl").write_text('{"t":"a"}\n{"t":"b"}\n\n', encoding="utf-8")
    (root / "r.json").write_text(json.dumps({"m": {"h": 0.875}}), encoding="utf-8")
    (root / "s.csv").write_text("Name,Requests/s\nAggregated,88.40965\n", encoding="utf-8")

    assert dn.derive({"kind": "grep_count", "globs": ["src/test/**/*.java"],
                      "pattern": "^\\s*@Test\\b"}, root) == 2
    assert dn.derive({"kind": "file_count", "globs": ["docs/adr/*.md"]}, root) == 1
    assert dn.derive({"kind": "jsonl_lines", "path": "d.jsonl"}, root) == 2
    assert dn.derive({"kind": "jsonl_count_where", "path": "d.jsonl",
                      "equals": {"t": "b"}}, root) == 1
    assert dn.derive({"kind": "json_field", "path": "r.json", "field": "m.h"}, root) == 0.875
    assert dn.derive({"kind": "csv_field", "path": "s.csv",
                      "where": {"Name": "Aggregated"}, "column": "Requests/s"}, root) == 88.40965


def test_md_table_row_locates_column_by_name_not_position(tmp_path: Path) -> None:
    """列按**表头名**定位：列顺序变化不得读错列（否则门闩会拿错数字比）。"""
    root = tmp_path / "r"
    root.mkdir()
    (root / "t.md").write_text(
        "| 口径 | p99 | p50 |\n| --- | --- | --- |\n"
        "| 服务端 TTFT | **16.0** | 7.0 |\n| 客户端端到端 | 46.33 | 21.77 |\n",
        encoding="utf-8")
    assert dn.derive({"kind": "md_table_row", "path": "t.md", "column": "p99",
                      "row_contains": "服务端 TTFT"}, root) == 16.0
    assert dn.derive({"kind": "md_table_row", "path": "t.md", "column": "p50",
                      "row_contains": "客户端端到端"}, root) == 21.77


def test_format_value_renders_doc_literals() -> None:
    assert dn.format_value(131, "int") == "131"
    assert dn.format_value(0.94, "decimal3") == "0.940"
    assert dn.format_value(88.40965, "decimal1") == "88.4"
    assert dn.format_value(1.0, "percent") == "100"
    assert dn.format_value(0.64, "percent") == "64"
    assert dn.format_value(0.925, "percent") == "92.5"


def test_registry_rejects_malformed_entry(tmp_path: Path) -> None:
    """登记表写错要报"登记表不可用"，而不是当成"文档与产物不一致"。"""
    root = tmp_path / "r"
    (root / "offline").mkdir(parents=True)
    bad = {"entries": [{"id": "x", "unit": "u", "derive": {"kind": "jsonl_lines", "path": "a"},
                        "format": "int", "cited_in": []}]}
    (root / "offline/doc_numbers.json").write_text(json.dumps(bad), encoding="utf-8")
    with pytest.raises(dn.RegistryError):
        dn.load_registry(root)
    assert dn.check(root) == 1


def _capture_check(root: Path) -> str:
    """跑一次 check 并把它写到 stderr 的失败说明取回来（报告文本本身是交付面）。"""
    import contextlib
    import io

    buf = io.StringIO()
    with contextlib.redirect_stderr(buf):
        rc = dn.check(root)
    assert rc == 1
    return buf.getvalue()


def test_registry_self_counts_are_derived_not_hardcoded() -> None:
    """#39：门闩自身的规模（条数/引用处数）必须由登记表现算，不得写死在文档里。"""
    entries = dn.load_registry(REPO)
    assert dn.derive({"kind": "registry_entries"}, REPO) == len(entries)
    assert dn.derive({"kind": "registry_citations"}, REPO) == sum(len(e["cited_in"]) for e in entries)


def test_mutation_e_registry_scale_turns_gate_red(tmp_path: Path) -> None:
    """变异 E：把 README 里的「N 条文档数字」改大 1 → 必须红且点名 doc_gate_entries。"""
    root = _mirror(tmp_path)
    entry = next(e for e in dn.load_registry(root) if e["id"] == "doc_gate_entries")
    cite = entry["cited_in"][0]
    val = _derived(root, "doc_gate_entries")
    hits = dn.citations(root, cite)
    assert hits, f"{cite['file']} 未按 {cite['pattern']!r} 引用"
    lineno, literal = hits[0]
    assert literal == val
    wrong = str(int(val) + 1)
    path = root / cite["file"]
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    lines[lineno - 1] = lines[lineno - 1].replace(literal, wrong, 1)
    path.write_text("".join(lines), encoding="utf-8")
    assert dn.check(root) == 1
    out = _capture_check(root)
    assert "doc_gate_entries" in out, out
    assert f"{cite['file']}:{lineno}" in out, out
