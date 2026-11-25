"""面板展示层的静态契约锁（#20 / #21 / #25）。

这三条都是「渲染结果看上去像真的，但其实是假的/看不见的」类缺陷——没有浏览器也能
静态拦住大部分回退：色条没背景色、引用了未定义的 CSS 变量、空采样时编出一个峰值。

判定刻意只看**单一事实源**（index.html 里的 CSS 规则与渲染表达式），不复制一份样式逻辑。

2026-11-20 A 版（事件响应工作台）落地后，下列检查的**选择器**已按新结构重标
（`.topbar`→`.top`、`--topbar-h`→`--top-h`、`.ts-app`→`.shell`、`#q`→`#quickq`），
断言语义不变：滤镜仍不得挂 body、顶栏高度仍必须是实测变量、桌面壳仍必须是定高网格、
label 仍必须关联控件、结论卡仍必须有可访问名称且排在命令清单之前。
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
    assert ".sat .top," in html, "滤镜应只作用于内容层（顶栏/视图/页脚）"


def test_topbar_height_is_a_variable_not_a_magic_number() -> None:
    """#23：顶栏会换行，sticky 偏移/弹层 top 必须读实测变量。"""
    html = _html()
    assert "--top-h" in html, "顶栏高度必须以 CSS 变量暴露（#23）"
    assert "top:53px" not in html, "禁止再把顶栏高度写死成 53px"
    shell = re.search(r"\.shell\{([^}]*)\}", html)
    assert shell, "桌面壳样式缺失（#23）"
    assert "display:grid" in shell.group(1) and "var(--rail)" in shell.group(1) \
        and "minmax(0,1fr)" in shell.group(1) and "overflow:hidden" in shell.group(1), \
        "桌面壳必须是定高网格（左栏/主区各自滚动，顶栏不再盖左栏；#23）"


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

def test_markdown_renders_tables_and_ordered_lists() -> None:
    """#26：答案渲染器要支持表格/有序列表，链接只放行 http(s)。"""
    html = _html()
    assert "function mdCells" in html, "表格解析缺失（mdCells）"
    assert "md-oli" in html and "md-no" in html, "有序列表必须保留编号（#26）"
    assert 'table class=\"md-t\"' in html, "表格必须渲染成 <table>（不是原样竖线）"
    assert "noopener noreferrer nofollow" in html, "外链必须 noopener/nofollow（模型输出不可信）"


def test_clarify_card_hides_retrieval_metrics() -> None:
    """#29：clarify 是零检索分支，不能显示 精排/检索 0ms/TTFT 0ms。"""
    html = _html()
    assert "(r.meta.gate || 'none') !== 'clarify'" in html, "检索类胶囊必须按 gate 过滤（#29）"
    assert "未做检索" in html, "clarify 的空命令文案要说明「未检索」，不能套用引用集话术"


def test_dialogs_are_keyboard_dismissible() -> None:
    """#30：弹层/抽屉/遮罩要能 Esc 关闭、Tab 不逃逸、有 dialog 语义。"""
    html = _html()
    assert "addEventListener('keydown'" in html and "Escape" in html, "缺 Esc 关闭（#30）"
    assert "trapTab" in html, "缺 Tab 焦点陷阱（浮层内循环）"
    assert 'aria-modal="true"' in html, "抽屉/遮罩需要 aria-modal 语义"


def test_answer_region_announces_updates() -> None:
    """#30：流式答案区必须是 live region，读屏才能听到增量。"""
    html = _html()
    assert 'aria-live="polite"' in html, "答案区缺 aria-live（#30）"


def test_form_labels_are_associated() -> None:
    """#30：label 必须与控件关联，不能是孤儿 label。"""
    html = _html()
    assert '<label for="quickq">' in html, "排障输入 label 需要 for=quickq"
    assert "-user\" data-bind=" in html, "身份输入框需要 id（label for 才能工作）"


def test_verdict_card_precedes_evidence_and_derives_from_answer() -> None:
    """产品化（2026-10-27）：结论必须先于证据；结论行只从答案「问题定位」派生，不许另造文案。"""
    html = _html()
    assert "function verdict(" in html, "结论卡渲染缺失"
    assert 'aria-label="诊断结论"' in html, "结论卡需要可访问名称"
    assert "function firstLine(" in html and "问题定位" in html, "结论行必须从答案的问题定位段派生"
    assert 'data-act="copymd"' in html, "缺「复制结论」导出入口"
    sections_start = html.index("function diagnosisSections(")
    assert html.index("verdict(r, k, inc)", sections_start) < html.index("只读命令清单", sections_start), \
        "结论卡必须排在证据链（命令清单）之前"


def test_mobile_font_floor_for_small_labels() -> None:
    """可读性（#36）：≤600px 时图例/脚注/页脚不得低于 12px（此前 10px）。"""
    html = _html()
    m = re.search(r"移动端可读性下限.*?@media \(max-width:600px\)\{(.*?)\n  \}", html, re.S)
    assert m, "缺移动端可读性下限块"
    block = m.group(1)
    for sel in (".pill", ".copy", ".vsub", ".ref-badge", ".statusbar"):
        assert sel in block, f"移动端字号下限缺少 {sel}"
    assert "font-size:12px" in block, "移动端字号下限必须是 12px"
    assert "grid-template-columns:1fr" in html, "窄屏步骤卡必须单列"


def test_reopen_sends_status_only() -> None:
    """结单后重开只能提交 status：夹带 timeline/diagnosis 会被服务端 409 拒绝。"""
    html = _html()
    assert "patchIncident(inc, { status: 'open' }, null, null, null, { content: false })" in html, \
        "重开必须剥离内容字段，只发 {\"status\":\"open\"}"


def test_right_rail_facts_do_not_inherit_panel_flex_layout() -> None:
    """右栏 `.facts` 曾命中运行面板的 flex 规则，把「处置记录」挤到标题右侧。"""
    html = _html()
    assert re.search(r"\.facts\{display:flex", html) is None, \
        "禁止用 .facts 选择器做 flex 行布局（会污染右栏本单事实）"
    assert "#degfacts{display:flex" in html, "降级状态机的行内事实应挂在 #degfacts 上"


def test_narrow_layout_can_grow_past_viewport() -> None:
    """≤900px 若 body 仍是 100% 高，底部状态栏会压住正文。"""
    html = _html()
    m = re.search(r"@media\(max-width:900px\)\{(.*?)\.rail\{", html, re.S)
    assert m, "缺 ≤900px 断点"
    assert "body{height:auto" in m.group(1), "窄屏 body 必须允许随文档增高"


def test_right_rail_survives_tablet_width() -> None:
    """1024px 曾把右栏压成 0 宽却保留内边距，内容溢出到视口外。"""
    html = _html()
    m = re.search(r"@media\(max-width:1180px\)\{(.*?)\n  \}", html, re.S)
    assert m, "缺 ≤1180px 断点"
    block = m.group(1)
    assert "minmax(0,1fr) 0}" not in block, "≤1180px 不能把第三列压成 0 宽还留内边距"
    assert re.search(r"grid-template-columns:[^;]*236px", block), \
        "≤1180px 右栏应保留可读宽度（236px）"


def test_mobile_facts_and_action_bar_exist() -> None:
    """≤900px 隐藏右栏后，事实与结单入口不能一起消失。"""
    html = _html()
    assert 'id="mfacts"' in html and "function mobileFacts" in html, \
        "缺窄屏「本单事实」折叠块"
    assert 'id="mbar"' in html and "function mobileBar" in html, \
        "缺窄屏底部结单操作条"
    assert "body.has-mbar{padding-bottom:60px}" in html, \
        "底部操作条必须给 body 留出安全区，避免遮住最后一行内容"


def test_my_filter_is_server_side_and_drops_local_backfill() -> None:
    """ADR-0033：「我的」必须走服务端 ?mine=true，且不得用 localOnly 回填。"""
    html = _html()
    assert 'data-filter="mine"' in html, "左栏缺「我的」筛选按钮"
    assert "p.set('mine', 'true')" in html, "「我的」必须请求服务端 mine=true"
    assert "S.filter !== 'mine' && incidentMatchesFilter(row)" in html, \
        "mine=true 时必须关闭 localOnly 回填（否则刚转交出去的单会回来）"
    assert "slice(0, 60)" not in html, "事故单列表不得再有 60 条客户端截断"


def test_transfer_entry_popover_and_failure_toast() -> None:
    """ADR-0033：页头转交入口、弹层（负责人/理由/最近 3 条）、失败必须 toast。"""
    html = _html()
    assert 'data-act="transfer"' in html, "页头操作区缺「转交」按钮"
    assert 'id="transferpop"' in html, "缺转交弹层容器"
    assert "function openTransferPop" in html and "function submitTransfer" in html, \
        "转交弹层与提交逻辑缺失"
    assert "/transfer" in html, "缺少 POST /{id}/transfer 调用"
    assert "最近 3 条转交" in html, "弹层必须列出最近 3 条转交历史"
    assert "转交失败：" in html, "转交失败必须 toast 明示，不得静默"
    assert "xferReason" in html and "maxlength=\"200\"" in html, "理由输入必须限制 200 字"


def test_sla_fact_and_semantic_colors() -> None:
    """ADR-0033：右栏事实显示「时限 / 剩余」，due_soon 用 warn、breached 用 err。"""
    html = _html()
    assert "['时限', slaFact(inc)]" in html, "右栏本单事实缺「时限」行"
    assert "剩余 " in html, "时限必须显示剩余时间"
    assert "未设时限" in html, "空优先级必须显示未设时限"
    assert "function slaTone" in html and "return 'warn'" in html and "return 'err'" in html, \
        "SLA 状态必须映射到既有 warn/err 语义"
    assert ".fact dd.warn{color:var(--warn)}" in html, "due_soon 必须用 warn 色"
    assert ".fact dd.err{color:var(--err)}" in html, "breached 必须用 err 色"


def test_incident_rail_uses_server_order_without_client_resort() -> None:
    """ADR-0038：排序唯一事实源在服务端；左栏不得二次排序（否则追加页会乱跳）。"""
    html = _html()
    assert "slaRank" not in html, "前端不再保留 SLA 排序函数（服务端是唯一事实源）"
    assert "let list = S.incidents.filter(incidentMatchesFilter);" in html, \
        "左栏必须按服务端返回顺序渲染"
    assert "S.incidents.slice().sort" not in html, "禁止客户端对服务端页再次排序"
    assert "已超时" in html and "临期" in html, "左栏缺 SLA 风险标记"


def test_incident_history_cursor_frontend_contract() -> None:
    """ADR-0038：加载更多追加、游标状态、1000 上限与服务端 status 筛选。"""
    html = _html()
    assert 'id="incMore"' in html and 'data-act="loadmore"' in html, "左栏底部缺「加载更多」"
    assert "function loadMoreIncidents" in html, "缺加载更多逻辑"
    assert "data.nextCursor" in html and "data.hasMore" in html, \
        "前端必须消费 nextCursor/hasMore"
    assert "S.incidentOpenCount" in html, "顶栏必须消费服务端 openCount"
    assert "已加载最近 1000 条，请用筛选缩小范围" in html, "到达 1000 上限必须有明确文案"
    assert "S.incidents.length >= 1000" in html, "客户端必须限制最多 1000 条"

    # 筛选走服务端；切换即重置游标并重拉第一页。
    assert "p.set('status', 'open')" in html and "p.set('status', 'closed')" in html, \
        "进行中/已结束必须走服务端 status="
    assert "S.incidentCursor = null;" in html and "refreshIncidents().then(() => renderAll())" in html, \
        "切换筛选必须重置游标并重拉第一页"

    # 追加页按 id 去重且不排序；单条回填同样按 id 去重。
    assert "known.has(row.id)" in html and "known.add(row.id)" in html, \
        "加载更多必须按 id 去重"
    assert html.count("x.id !== inc.id") >= 2, "创建与通知/告警单条回填都必须按 id 去重"
    assert "本租户还没有事故单" in html and "已加载的页里没有匹配的事故单" in html, \
        "空态必须区分「本租户没有事故单」与「已加载页无匹配」"


def test_alert_open_uses_single_row_backfill_without_resetting_cursor() -> None:
    """告警入口打开未加载事故单必须单条回填，不得 refresh 重置列表与游标。"""
    html = _html()
    start = html.index("if (act === 'openalertinc')")
    end = html.index("if (act === 'openacct')", start)
    block = html[start:end]
    assert "focusIncidentById(t.dataset.openid)" in block, \
        "告警打开必须复用通知路径的单条回填"
    assert "refreshIncidents" not in block, \
        "告警打开不得调用 refreshIncidents（会把列表与游标重置到第一页）"
    assert "outcome === 'not_found'" in block, \
        "只有真正 404 才显示跨租户/不存在提示"
    assert "该事故单不在当前身份的左栏" in block, "404 提示文案必须保留"

    focus_start = html.index("async function focusIncidentById")
    focus_end = html.index("/* ==================== 评论", focus_start)
    focus_block = html[focus_start:focus_end]
    assert "e.status === 404" in focus_block, "单条回填必须识别真正的 404"
    assert "incidentCursor" not in focus_block and "incidentHasMore" not in focus_block, \
        "单条回填不得改写分页状态"


def test_incident_requests_discard_stale_filter_and_keep_zero_open_count() -> None:
    """翻页与筛选并发时丢弃旧响应；openCount=0 必须能真正刷新。"""
    html = _html()
    assert "const requestFilter = S.filter;" in html, "请求前必须捕获筛选"
    assert "const requestCursor = S.incidentCursor;" in html, "请求前必须捕获游标"
    assert "requestSeq !== S.incidentRequestSeq" in html, "必须用请求代际丢弃旧刷新"
    assert "S.incidentCursor !== requestCursor" in html, "必须用捕获游标丢弃旧追加页"
    assert "incidentFilterQuery(requestFilter, requestCursor)" in html, \
        "加载更多必须使用捕获的筛选与游标，而不是回调时的状态"
    assert "const openCount = Number(data.openCount);" in html
    assert "if (Number.isFinite(openCount)) S.incidentOpenCount = openCount;" in html, \
        "openCount=0 不能被 || 旧值吞掉"


def test_discussion_card_sits_between_diagnosis_and_timeline() -> None:
    """ADR-0034：讨论卡片必须在「诊断分析」之后、「处置记录」之前。"""
    html = _html()
    assert "function discussionCard" in html, "缺讨论卡片渲染函数"
    assert '<span class="k">讨论</span>' in html, "讨论卡片标题缺失"
    ws = html.index("function renderWorkspace()")
    block = html[ws:ws + 4000]
    assert "diagnosisSections(S.a.r, 'a', inc) + discussionCard(inc) + timelineCard(inc)" in block, \
        "有诊断时讨论必须夹在诊断分析与处置记录之间"
    assert "emptyDiagnosis(inc) + discussionCard(inc) + timelineCard(inc)" in block, \
        "无诊断时讨论也必须存在（评论不依赖诊断）"
    assert ".cm.own{" in html and ".cm.mentioned{" in html, \
        "自己的评论与 @ 到我的评论必须有可区分的视觉标记"


def test_discussion_has_mention_completion_from_assignees() -> None:
    """ADR-0034：@ 补全复用 GET /assignees，候选按钮走 data-mention。"""
    html = _html()
    assert 'id="commentBox"' in html and 'maxlength="2000"' in html, "评论输入框缺失或未限 2000 字"
    assert "function mentionToken" in html and "function insertMention" in html, "缺 @ 补全逻辑"
    assert "function updateMentionPop" in html and "data-mention=" in html, "缺 @ 候选列表"
    assert "/api/v1/copilot/incidents/assignees" in html, "@ 候选必须复用 /assignees，不另造数据源"
    assert "data-act=\"sendcomment\"" in html, "缺发送按钮"


def test_unread_watermark_uses_session_storage_only() -> None:
    """ADR-0034：未读角标基于 commentId 水位，只存 sessionStorage。"""
    html = _html()
    assert "opspilot.comments.seen" in html, "未读水位键名必须是 opspilot.comments.seen"
    assert "sessionStorage.setItem(SEEN_KEY" in html, "未读水位必须写 sessionStorage"
    assert "localStorage.setItem(SEEN_KEY" not in html, "未读水位不得写 localStorage（AC9）"
    assert "function commentUnread" in html and 'class="inc-unread"' in html, \
        "左栏缺未读角标渲染"


def test_comment_failure_keeps_draft_and_clears_only_on_success() -> None:
    """ADR-0034 硬要求：发送失败 toast 明示且保留草稿，不得先清空输入框。"""
    html = _html()
    assert "评论发送失败" in html, "发送失败必须 toast 明示"
    assert "S.commentDraft[inc.id] = draft" in html, "失败路径必须把草稿写回状态"
    assert "again.value = draft" in html, "失败路径必须恢复 textarea 内容"
    cleared = html.index("delete S.commentDraft[inc.id]")
    kept = html.index("S.commentDraft[inc.id] = draft")
    assert cleared < kept, "草稿只能在成功路径清除；失败路径必须保留"


def test_alert_intake_block_is_before_results_and_uses_row_tenant() -> None:
    """ADR-0035：运行面板「告警入口」区块必须真实取数、带行租户、空态不摆样例。"""
    html = _html()
    assert "<h2>告警入口" in html, "缺「告警入口」区块"
    assert 'id="alerts"' in html, "告警入口缺渲染容器"
    assert html.index("<h2>告警入口") < html.index("排障成果流"), \
        "告警入口必须排在「排障成果流」之前（契约 AC7）"
    assert "/api/v1/admin/alerts/recent" in html, "面板必须读 /admin/alerts/recent"
    assert "alertSince" in html and "&limit=50" in html, "增量水位与单页上限必须存在"

    # R1：建单请求的 tenant 只能来自该告警行，不得自行猜测。
    assert 'data-act="alertinc"' in html, "缺「建事故单」按钮"
    assert 'data-tenant="' in html, "建单按钮必须携带该行的 tenant"
    assert "/incident?tenant=" in html, "建单请求必须带 tenant 查询参数"
    assert "encodeURIComponent(tenant)" in html, "tenant 必须按 URL 组件编码"
    assert "打开事故单" in html, "已有 incident_id 的行必须显示「打开事故单」"

    # 空态：写明数据源与保留窗口，且明确不是样例数字。
    assert "尚无告警记录" in html, "缺空态文案"
    assert "不是示例数据" in html, "空态必须说明没有摆样例"
    assert "retention_days" in html and "max_per_tenant" in html, \
        "保留窗口必须来自接口字段，不得在前端写死"
    assert "建事故单失败：" in html, "建单失败必须 toast 明示"


def test_alert_truncated_is_consumed_and_labelled() -> None:
    """ADR-0035 / R2：since 是新告警水位不是游标；truncated=true 必须显式告知只看到一部分。"""
    html = _html()
    assert "仅显示最近 " in html, "truncated=true 时面板必须显示「仅显示最近 N 条」"
    assert "meta.truncated" in html, "renderAlerts 必须读取响应里的 truncated"
    assert "S.alertTruncated" in html, "增量轮询不得把首屏的 truncated 事实覆盖掉"
    assert "仅显示最近 ' + S.alerts.length" in html, "N 必须取当前渲染行数（与表体同口径）"


def test_cross_tenant_incident_toast_names_tenant_and_id() -> None:
    """ADR-0035 / R2：跨租户建单不静默、不跨租户展示，toast 必须带租户与本单号。"""
    html = _html()
    assert "已为 ' + tenant + ' 建单 ' + inc.id + '，切换到该租户身份可查看', 'warn')" in html, \
        "跨租户建单必须 toast「已为 <tenant> 建单 <id>，切换到该租户身份可查看」"


def test_notification_bell_panel_and_read_all() -> None:
    """ADR-0037：顶栏铃铛 + 未读角标 + 下拉面板 + 全部已读，缺一即静默失明。"""
    html = _html()
    assert 'id="notifybtn"' in html, "顶栏缺通知铃铛"
    assert 'data-popbtn="notifypop"' in html, "铃铛必须打开通知面板"
    assert 'id="notifybadge"' in html and "notifyUnread" in html, "缺未读角标"
    assert 'id="notifypop"' in html, "缺通知下拉面板"
    assert "function renderNotifications" in html and "data-notify=" in html, "面板缺通知行渲染"
    assert 'data-act="notifyreadall"' in html and "全部已读" in html, "缺「全部已读」入口"
    assert "async function openNotification" in html, "点击通知的跳转逻辑缺失"
    assert "const NOTIFY_API = '/api/v1/copilot/notifications';" in html, \
        "通知端点必须是字面量，禁止拼接规避面板契约第 7 层"
    assert "'/api/v1/' + 'copilot/notifications'" not in html, \
        "不得用字符串拼接绕开面板契约的路径字面量比对"
    assert "'/' + encodeURIComponent(id) + '/read'" in html, "点击通知必须调用 /{id}/read"
    assert "function focusIncidentById" in html, "点击通知必须跳转对应事故"
    assert "暂无通知" in html, "空态必须有说明，不能摆样例"


def test_notification_polling_is_30s_and_refreshes_on_focus() -> None:
    """ADR-0037：30s 轮询 + 窗口重新聚焦刷新；切回来看到的是当前未读。"""
    html = _html()
    assert "setInterval(refreshNotifications, 30000)" in html, "通知轮询必须是 30s"
    assert "window.addEventListener('focus'" in html, "窗口聚焦时必须刷新通知"
    assert "document.visibilityState === 'visible'" in html, "标签页重新可见时必须刷新"
    assert "startNotifyPoll()" in html, "登录/启动路径必须启动通知轮询"
    assert "stopNotifyPoll()" in html, "身份失效时必须停止通知轮询"


def test_notification_keeps_existing_storage_keys_and_hides_on_404() -> None:
    """ADR-0037：不新增 localStorage 键、不动既有两键；接口 404 时铃铛隐藏。"""
    html = _html()
    assert "const ACTIVE_KEY = 'opspilot.incidents.active';" in html
    assert "const SEEN_KEY = 'opspilot.comments.seen';" in html
    assert "localStorage.setItem('opspilot.notif" not in html, "通知不得新增 localStorage 键"
    assert "sessionStorage.setItem('opspilot.notif" not in html, "通知不得新增 sessionStorage 键"
    assert "S.notifyHidden = true" in html and "btn.hidden = !show" in html, \
        "灰度/回滚期接口 404 时铃铛必须隐藏，不阻塞事故页"
