# -*- coding: utf-8 -*-
"""对外面用词门闩的回归锁（`scripts/check_public_copy.py`，ADR-0023 决策 1–4）。

为什么这些用例必须存在：这道门闩有一类**静默失效**——词表来源（`CONTEXT.md` 的 `_对外_` 行）
改了格式，解析器悄悄返回空集，门闩从此恒绿却仍然"在跑"。这比没有门闩更危险（本仓对空转门闩
已有前科：CI 里用 mtime 判同代）。故锁四件事：

  1. **逐词反向验证**——对**每一个**登记词，把它塞进对外面都必须变红。漏掉一个 = 那条词表是死的。
  2. **词表来源不可为空**——`CONTEXT.md` 解析不到任何 `_对外_` 别名时**报错**，不得静默放行。
  3. **对外面缺文件即红**——HTML/README 少一个，门闩就少看一个面。
  4. **反向只报不闸**——ADR-0023 决策 4 写"宁可漏报不误报"：未登记的加粗机制名进 `INFO`、
     不改退出码（做成硬闸会逼出"为过闸而改写的奇怪句子"）。

变异一律在**仓库镜像**上做，仓库本体永不被测试改脏。判据极性由本文件钉着——跑红时先怀疑被测面
真变了，不是门闩抽风。
"""
from __future__ import annotations

import contextlib
import io
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts"))

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把不变量改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
import check_public_copy as cc  # noqa: E402

_NEEDED = (cc.CONTEXT_REL, *cc.PUBLIC_FILES)


def _mirror(base: Path) -> Path:
    """把门闩依赖的文件集复制成一份可随意改坏的仓库镜像。"""
    root = base / "repo"
    for rel in _NEEDED:
        src, dst = REPO / rel, root / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(src.read_bytes())
    return root


def _run(root: Path) -> tuple[int, str, str]:
    """跑一次 check，把 stdout/stderr 都取回来（报告文本本身是交付面）。"""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        rc = cc.check(root)
    return rc, out.getvalue(), err.getvalue()


# ---------------------------------------------------------------- 正向：真实仓库

def test_real_repo_is_green() -> None:
    """正常流程不得红——会假红的门闩比没有门闩更快被关掉。"""
    assert cc.check(REPO) == 0


def test_real_repo_is_green_twice() -> None:
    """连跑两次同结果：判据不得依赖顺序或运行残留。"""
    assert (cc.check(REPO), cc.check(REPO)) == (0, 0)


def test_real_repo_registry_has_the_aliased_terms() -> None:
    """解析器在真实 `CONTEXT.md` 上必须认出那批登记过 `_对外_` 的词（格式一变即红）。"""
    reg = cc.registry(REPO)
    assert {"指纹", "单飞", "对照模式", "变异验证", "行动契约", "存储模式"} <= set(reg)
    for canonical, alias in reg.items():
        assert canonical and alias, f"{canonical!r} 的别名是空的"
    # 没登记别名的 canonical 词不得进"禁用面"表（否则会误禁正常用词）。
    assert "覆盖范围" not in reg


def test_parse_synthetic_context(tmp_path: Path) -> None:
    """两种条目形态都要认：名字独占一行 + 正文在下一行；以及单行条目。"""
    root = tmp_path / "r"
    (root / "docs").mkdir(parents=True)
    (root / "docs/CONTEXT.md").write_text(
        "**甲（A）**:\n正文甲。_对外_: 甲别名。_Avoid_: 别的\n"
        "**乙**: 单行条目正文，无别名\n"
        "**丙**:\n正文丙。_对外_: 无对外别名\n",
        encoding="utf-8")
    assert cc.registry(root) == {"甲": "甲别名"}
    assert cc.all_terms(root) == {"甲", "乙", "丙"}
    # 决策 1 的完备性：只有"乙"没写 `_对外_` 行（"丙"写了"无对外别名"，不算缺）。
    assert cc.terms_missing_alias_line(root) == ["乙"]


def test_every_canonical_term_declares_an_alias_line() -> None:
    """决策 1 的完备性：真实 `CONTEXT.md` 里每条 canonical term 都有 `_对外_` 行。"""
    assert cc.terms_missing_alias_line(REPO) == []


# ---------------------------------------------------------------- 变异：每个词都必须真被拦

def test_mutation_every_canonical_term_is_actually_forbidden(tmp_path: Path) -> None:
    """对**每个**登记词逐一验证：漏掉一个 = 那一条词表在门闩里是死的（形式主义）。"""
    for term in cc.registry(REPO):
        root = _mirror(tmp_path / term)
        readme = root / "README.md"
        readme.write_text(readme.read_text(encoding="utf-8") + f"\n{term}\n", encoding="utf-8")
        rc, _, err = _run(root)
        assert rc == 1, f"canonical「{term}」塞进 README 却没被拦下"
        assert term in err, err


def test_mutation_html_visible_text_turns_red(tmp_path: Path) -> None:
    """静态可见文案里的 canonical 词必须红，且点名文件:行。"""
    root = _mirror(tmp_path)
    page = root / "src/main/resources/static/index.html"
    page.write_text("<p>指纹</p>\n" + page.read_text(encoding="utf-8"), encoding="utf-8")
    rc, _, err = _run(root)
    assert rc == 1
    assert "index.html:1" in err
    assert "指纹" in err


def test_mutation_html_script_label_turns_red(tmp_path: Path) -> None:
    """**加严面**：`<script>` 里渲染页面标签的字符串也算对外面（否则页面黑话会漏网）。"""
    root = _mirror(tmp_path)
    page = root / "src/main/resources/static/index.html"
    text = page.read_text(encoding="utf-8")
    assert "<script>" in text
    page.write_text(text.replace("<script>", "<script>\nconst __lbl = '在途指纹';\n", 1),
                    encoding="utf-8")
    rc, _, err = _run(root)
    assert rc == 1
    # 行号不硬编码（页面增删一行就换行号，硬编码会把"断言过时"伪装成"门闩坏了"）。
    assert "index.html:" in err, err
    assert "指纹" in err, err


def test_mutation_second_html_is_also_scanned(tmp_path: Path) -> None:
    """两个 HTML 都在对外面里：只扫 `index.html` 会漏掉薄壳页。"""
    root = _mirror(tmp_path)
    page = root / "src/main/resources/static/troubleshoot.html"
    page.write_text(page.read_text(encoding="utf-8").replace("</body>", "<p>指纹</p>\n</body>"),
                    encoding="utf-8")
    rc, _, err = _run(root)
    assert rc == 1
    assert "troubleshoot.html" in err


# ---------------------------------------------------------------- 反向边界：刻意不覆盖的面

def test_style_block_is_not_part_of_public_surface(tmp_path: Path) -> None:
    """边界：`<style>` 是纯样式、不含文案，剥掉——CSS 注释里的词不算对外文案。"""
    root = _mirror(tmp_path)
    page = root / "src/main/resources/static/index.html"
    text = page.read_text(encoding="utf-8")
    assert "<style>" in text
    page.write_text(text.replace("<style>", "<style>\n/* 指纹 */\n", 1), encoding="utf-8")
    rc, _, _ = _run(root)
    assert rc == 0


def test_unregistered_bold_phrase_is_advisory_only(tmp_path: Path) -> None:
    """反向只报不闸：未登记的加粗机制名进 INFO，退出码仍 0（ADR-0023 决策 4）。"""
    root = _mirror(tmp_path)
    readme = root / "README.md"
    readme.write_text(readme.read_text(encoding="utf-8") + "\n**熔断风暴抑制**\n", encoding="utf-8")
    rc, out, _ = _run(root)
    assert rc == 0
    assert "熔断风暴抑制" in out


def test_registered_alias_bold_phrase_is_not_advisory(tmp_path: Path) -> None:
    """登记过的别名（canonical 或别名任一）不得进 INFO——否则反向恒噪声、会被无视。"""
    root = _mirror(tmp_path)
    readme = root / "README.md"
    readme.write_text(readme.read_text(encoding="utf-8") + "\n**只读命令清单**\n", encoding="utf-8")
    rc, out, _ = _run(root)
    assert rc == 0
    assert "只读命令清单" not in out


# ---------------------------------------------------------------- 门闩自身不可静默失效

def test_empty_registry_is_an_error_not_a_silent_pass(tmp_path: Path) -> None:
    """词表来源空了必须报错——"悄悄失效的门闩"比没有门闩更危险。"""
    root = _mirror(tmp_path)
    (root / "docs/CONTEXT.md").write_text("# 一条术语都没有\n", encoding="utf-8")
    rc, _, err = _run(root)
    assert rc == 1
    assert "空转" in err


def test_mutation_missing_alias_line_turns_red(tmp_path: Path) -> None:
    """变异：抹掉某条的 `_对外_` 行 → 必须红并点名该术语（决策 1 的完备性闸）。"""
    root = _mirror(tmp_path)
    context = root / "docs/CONTEXT.md"
    text = context.read_text(encoding="utf-8")
    assert "_对外_: 无对外别名。" in text
    context.write_text(text.replace("_对外_: 无对外别名。", "", 1), encoding="utf-8")
    rc, _, err = _run(root)
    assert rc == 1
    assert "没有 `_对外_` 行" in err


def test_missing_public_file_is_an_error(tmp_path: Path) -> None:
    """对外面缺一个文件 = 门闩少看一个面，必须红而不是跳过。"""
    root = _mirror(tmp_path)
    (root / "src/main/resources/static/troubleshoot.html").unlink()
    rc, _, err = _run(root)
    assert rc == 1
    assert "troubleshoot.html" in err
