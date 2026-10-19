# -*- coding: utf-8 -*-
"""对外面用词门闩（ADR-0023 决策 1–4）——术语双轨制的**正向**那一半。

背景：本仓的术语是为精确而生的（`docs/CONTEXT.md` 逐条论证近义词为什么被否），代价是它们
会漏到不该出现的地方——README 首屏、页面标签。ADR-0023 把这件事拆成双轨：canonical 留在
`CONTEXT.md` / ADR / `OPS.md` / 代码注释 / 协议键，**对外面**（README 全文 + 页面文案）用朴素词。

本脚本钉两条**可机器判**的硬闸：

1. **完备性**（决策 1）：每条 canonical term 都要有一行 `_对外_: …`（没有对外别名就写
   `_对外_: 无对外别名。`）。没有它，就分不清"有意不设别名"与"忘了登记"。
2. **正向**（决策 1/3）：对外面不得出现**登记了真别名**的 canonical 词。词表**不手写**——从
   `docs/CONTEXT.md` 现取。登记一个术语 = 同时登记了它在对外面的禁用面，两件事不可能只做一件。

登记表空了会报错而不是静默放行——"悄悄失效的门闩"比"没有门闩"更危险。

反向（启发式）刻意**只报不闸**：ADR-0023 决策 4 写"宁可漏报不误报"，而实测严格过滤（纯中文
2–8 字加粗短语）在现 README 上仍有若干噪声（"边界 / 排障台 / 运行面板"这类**领域常用词**，
不是新造机制名）。把它做成硬闸就会逼出"为过闸而改写的奇怪句子"——正是该 ADR 后果段点名要
避免的失效方式。故反向输出 `INFO` 行供人过目，不影响退出码。

对外面的定义（ADR-0023 决策 2）：`README.md` 全文 + 两个 HTML 的可见文案。HTML 这里**只剥
`<style>`、不剥 `<script>`**——这是对 ADR 字面定义的一处**有意加严**：面板的用户可见标签
（如"在途合并组"）由 JS 字符串字面量渲染，整段剥掉 `<script>` 会把页面上的黑话放行，与决策 4
自己写的"页面上的机制名标签"相矛盾。canonical 词全是中文、代码标识符全是 ASCII，故不剥 script
不引入误报；`<style>` 是纯样式、不含文案，照剥。

只用 stdlib，不触网；`--check` 可在 CI 零凭据运行（与 `provenance.py` / `doc_numbers.py` 同处）。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONTEXT_REL = "docs/CONTEXT.md"
PUBLIC_FILES = (
    "README.md",
    "src/main/resources/static/index.html",
    "src/main/resources/static/troubleshoot.html",
)

# 条目行：`**指纹（Fingerprint）**:`（名字独占一行，正文在下一行）/ `**Level 0（正常）**: 正文`
# （单行条目）。canonical = 名字里括号前的中文；`_对外_` 出现在条目名字行或其后任一正文行。
_ENTRY = re.compile(r"^\*\*(?P<name>[^*]{1,30})\*\*:")
# 条目正文的右边界：遇到下一个标题或分隔线即止（否则上一节的末条会吞掉下一节的标题）。
_HEAD = re.compile(r"^#{1,6}\s")
# `_对外_: 去重键。_Avoid_: …` / `_对外_: 权限隔离演练（…）` —— 别名止于下划线或左括号。
_ALIAS = re.compile(r"_对外_:\s*(?P<alias>[^_（(]+)")
# 反向启发式：纯中文、2–8 字的加粗短语才当"机制名"候选（宁可漏报不误报）。
_BOLD = re.compile(r"\*\*([^*\n]{1,40})\*\*")
_CJK_NAME = re.compile(r"^[\u4e00-\u9fff]{3,8}$")
# 含这些"功能字"的加粗短语是**强调句**（"不承诺这些数字"/"与生产同一份"），不是机制名——排除掉，
# 否则反向会退化成全文风格审查（ADR-0023 明说不要）。宁可漏报：真实机制名（如"熔断风暴抑制"）
# 通常不含这些字。
_GENERIC = set("的了是不也在与或和把被让会能要请用到从对为有无非一个我你这那都只更最即等如若则但而因所可应须必需要先再又还就才很太全每各某些多少大小高低长短快慢三两")


class GateError(Exception):
    """门闩自身的问题（登记表读不到 / 对外面文件缺失），区别于"文案违规"。"""


def _lines(path: Path) -> list[str]:
    """按行读取；通用换行会把 CRLF 归一，故行内匹配与检出平台无关。"""
    try:
        return path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise GateError(f"{path} 读取失败：{exc}")


def entries(root: Path) -> list[tuple[str, list[str]]]:
    """[(canonical 名, 该条目的行)]——条目 = 名字行 + 其后正文行，止于下一条目名 / 标题 / 分隔线。"""
    path = root / CONTEXT_REL
    if not path.exists():
        raise GateError(f"{CONTEXT_REL} 不存在")
    lines = _lines(path)
    starts = [i for i, l in enumerate(lines) if _ENTRY.match(l)]
    out: list[tuple[str, list[str]]] = []
    for k, s in enumerate(starts):
        e = starts[k + 1] if k + 1 < len(starts) else len(lines)
        end = e
        for j in range(s + 1, e):
            if _HEAD.match(lines[j]) or lines[j].startswith("---"):
                end = j
                break
        name = _ENTRY.match(lines[s]).group("name").split("（")[0].split("(")[0].strip()
        if name:
            out.append((name, lines[s:end]))
    if not out:
        raise GateError(f"{CONTEXT_REL} 里没解析到任何术语条目——词表来源空了，门闩会空转")
    return out


def _alias_of(span: list[str]) -> str | None:
    """条目里 `_对外_: …` 行的别名；没有这一行返回 None，写了"无对外别名"返回 ""。"""
    for line in span:
        a = _ALIAS.search(line)
        if a:
            alias = a.group("alias").strip().rstrip("。，、")
            return "" if alias == "无对外别名" else alias
    return None


def registry(root: Path) -> dict[str, str]:
    """canonical → 对外别名；只收**登记了真别名**的条目（"无对外别名"不入表 = 不禁用）。"""
    out = {name: alias for name, span in entries(root) if (alias := _alias_of(span))}
    if not out:
        raise GateError(f"{CONTEXT_REL} 里没解析到任何 `_对外_` 别名——词表来源空了，门闩会空转")
    return out


def all_terms(root: Path) -> set[str]:
    """CONTEXT.md 里**全部** canonical 术语名——反向登记面的真值。"""
    return {name for name, _ in entries(root)}


def terms_missing_alias_line(root: Path) -> list[str]:
    """**决策 1 的完备性**：每条 canonical term 都要有一行 `_对外_: …`（没有别名写"无对外别名"）。

    这一条为什么值得闸：没有它，就分不清"这条术语是**有意**不设别名"还是"**忘了**登记"——而
    `_对外_` 表是别名登记与门闩词表**共用**的单一事实源（ADR-0023 决策 1/后果段）。
    """
    return [name for name, span in entries(root) if _alias_of(span) is None]


def _html_visible(lines: list[str]) -> list[tuple[int, str]]:
    """剥掉 `<style>` 块后的行（保留 `<script>`——页面标签由它的字符串字面量渲染，见 docstring）。"""
    out: list[tuple[int, str]] = []
    in_style = False
    for i, line in enumerate(lines, 1):
        low = line.lower()
        if in_style:
            if "</style" in low:
                in_style = False
            continue
        if "<style" in low:
            if "</style" not in low:
                in_style = True
            continue
        out.append((i, line))
    return out


def public_lines(root: Path) -> list[tuple[str, int, str]]:
    """[(相对路径, 行号, 对外可见文本)]——对外面的单一事实源。"""
    out: list[tuple[str, int, str]] = []
    for rel in PUBLIC_FILES:
        path = root / rel
        if not path.exists():
            raise GateError(f"{rel} 不存在（对外面缺文件 = 门闩空转）")
        lines = _lines(path)
        if path.suffix == ".html":
            out.extend((rel, i, t) for i, t in _html_visible(lines))
        else:
            out.extend((rel, i, t) for i, t in enumerate(lines, 1))
    return out


def violations(root: Path) -> list[str]:
    """正向判据：对外面出现的每个 canonical 词都是一条违规。"""
    reg = registry(root)
    hits: list[str] = []
    for rel, lineno, text in public_lines(root):
        for name in reg:
            if name in text:
                hits.append(f"{rel}:{lineno} 出现 canonical「{name}」→ 对外应用「{reg[name]}」")
    return hits


def advisory(root: Path) -> list[str]:
    """反向启发式：README 里**未登记**的加粗机制名候选（只报不闸，去重且保序）。"""
    reg = registry(root)
    known = all_terms(root) | set(reg) | set(reg.values())
    seen: set[str] = set()
    out: list[str] = []
    for line in _lines(root / "README.md"):
        for phrase in _BOLD.findall(line):
            p = phrase.strip()
            if not _CJK_NAME.match(p) or p in seen:
                continue
            seen.add(p)
            if any(ch in _GENERIC for ch in p):
                continue
            # 命中任一登记词（canonical 或别名，含互为子串）即视为"登记过"。
            if any(k in p or p in k for k in known):
                continue
            out.append(p)
    return out


def check(root: Path) -> int:
    try:
        reg = registry(root)
        missing = terms_missing_alias_line(root)
        hits = violations(root)
        adv = advisory(root)
    except GateError as exc:
        print(f"FAIL 门闩不可用：{exc}", file=sys.stderr)
        return 1
    if adv:
        print("INFO 反向启发式（只报不闸，人工过目）：以下 README 加粗短语未在 CONTEXT.md 登记 → "
              + "、".join(adv))
    if missing:
        print(f"FAIL {len(missing)} 条 canonical term 没有 `_对外_` 行（ADR-0023 决策 1）——"
              "没有对外别名就写 `_对外_: 无对外别名。`：", file=sys.stderr)
        for name in missing:
            print(f"  - {name}", file=sys.stderr)
        return 1
    if hits:
        print(f"FAIL 对外面出现 {len(hits)} 处 canonical 词（ADR-0023 决策 1/3）：", file=sys.stderr)
        for h in hits:
            print(f"  - {h}", file=sys.stderr)
        return 1
    print(f"OK 对外面用词一致：{len(reg)} 个 canonical 词在 README + 页面文案里零出现")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="对外面用词门闩（ADR-0023 决策 1–4）")
    ap.add_argument("--check", action="store_true", help="CI 形态：违规则退出码 1（本脚本默认即此行为）")
    ap.add_argument("--root", default=str(ROOT), help="仓库根（默认脚本的上一级）")
    args = ap.parse_args(argv)
    return check(Path(args.root).resolve())


if __name__ == "__main__":
    sys.exit(main())
