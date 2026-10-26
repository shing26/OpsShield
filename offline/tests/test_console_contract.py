"""面板展示层的静态契约锁（#20 / #21 / #25）。

这三条都是「渲染结果看上去像真的，但其实是假的/看不见的」类缺陷——没有浏览器也能
静态拦住大部分回退：色条没背景色、引用了未定义的 CSS 变量、空采样时编出一个峰值。

判定刻意只看**单一事实源**（index.html 里的 CSS 规则与渲染表达式），不复制一份样式逻辑。
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INDEX = ROOT / "src" / "main" / "resources" / "static" / "index.html"


def _html() -> str:
    return INDEX.read_text(encoding="utf-8")


def test_bar_segments_have_a_background() -> None:
    """#20：分段耗时条渲染了 <i> 却没有背景色 → 整条不可见。"""
    m = re.search(r"\.bar i\{([^}]*)\}", _html())
    assert m, ".bar i 的样式规则缺失（分段耗时条将无法渲染）"
    assert "background" in m.group(1), ".bar i 必须给背景色，否则面板上整条不可见（#20）"


def test_no_undefined_css_variables() -> None:
    """#21：L1 卡片曾用 --bd/--fg2/--bg2，而 :root 从未定义它们 → 边框/底色整条失效。"""
    html = _html()
    defined = set(re.findall(r"(--[a-z0-9-]+)\s*:", html))
    used = set(re.findall(r"var\((--[a-z0-9-]+)\)", html))
    missing = sorted(used - defined)
    assert not missing, f"引用了未定义的 CSS 变量: {missing}（要么补定义，要么改用既有变量）"


def test_rate_caption_does_not_invent_a_peak() -> None:
    """#25：零采样时不能对外写「峰值 1 次/10s」；峰值必须是采样现算。"""
    html = _html()
    assert "const hasData = peakRaw > 0;" in html, "到达率图必须先从原始采样得出 hasData"
    assert "峰值 ' + (hasData ? peakRaw : '—')" in html, \
        "峰值展示必须按 hasData 分支（空数据写 —），不能把柱高兜底值当对外数字"


def test_diff_table_requires_both_sides() -> None:
    """#25：B 未查询时差集表不能给出 B=0 这类数字结论。"""
    html = _html()
    assert "两侧都完成后才有对照" in html, "对照表缺一侧时必须只给说明、不给表"

def test_popover_is_viewport_anchored() -> None:
    """#28：弹层必须相对视口定位；absolute + 文档坐标会在滚动后整块跑出视口。"""
    html = _html()
    m = re.search(r"\.pop\{([^}]*)\}", html)
    assert m, ".pop 样式缺失"
    assert "position:fixed" in m.group(1), "弹层必须 position:fixed（#28：滚动后不可见）"


def test_filter_is_not_applied_to_body() -> None:
    """#22：body 上的 filter 会为 fixed 后代创建包含块，把凭证遮罩改成相对整页定位。"""
    html = _html()
    assert "body.sat{filter" not in html, "滤镜不能挂在 body 上（#22）"
    assert ".sat .topbar" in html, "滤镜应只作用于内容层（顶栏/视图/页脚）"


def test_topbar_height_is_a_variable_not_a_magic_number() -> None:
    """#23：顶栏会换行，sticky 偏移/弹层 top 必须读实测变量。"""
    html = _html()
    assert "--topbar-h" in html, "顶栏高度必须以 CSS 变量暴露（#23）"
    assert "top:53px" not in html, "禁止再把顶栏高度写死成 53px"
    assert ".ts-app{display:grid;grid-template-columns:300px 1fr;height:100%" in html, \
        "桌面壳必须是定高两列（左栏/主区各自滚动，顶栏不再盖左栏；#23）"


def test_mobile_kpis_are_two_columns() -> None:
    """#24：≤600px 时 KPI 不能一列五屏。"""
    html = _html()
    assert "max-width:600px" in html, "缺窄屏补充断点（#24）"
    assert "grid-template-columns:1fr 1fr" in html, "窄屏 KPI 必须是两列"


def test_reingest_timestamp_is_display_formatted() -> None:
    """#24：纳秒精度 ISO 原串不该直接进面板文案。"""
    html = _html()
    assert "fmtTs(ri.last)" in html, "reingest 时间必须走 fmtTs 显示层格式化"
    assert "fmtTs" in html and "replace(/(\\d{4}" in html, "fmtTs 必须真的截掉小数秒"
