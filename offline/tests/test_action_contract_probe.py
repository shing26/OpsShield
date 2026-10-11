# -*- coding: utf-8 -*-
r"""只读行动契约探针的回归锁（`offline/action_contract_probe.py`）。

为什么这些用例必须存在：这份探针是 ADR-0017 四条不变式在**线上流量**上的唯一度量，而它的
每条判据都有两条静默失效路径，两条都不报错、只会让人以为这里被保护着：

  1. **判据挂在空气上**——折叠空白 / 段名闸 / 闭包 / 红线绊线的实现被换成恒真或恒空形状，
     于是"零幻觉""不出租户"变成恒真式。这正是本项目对空转门闩的前科类型。
  2. **口径漂移**——探针的折叠规则与 Java 侧 `normalizedSource` 漂移（比如不再合续行），
     线上明明逐字的命令被判成幻觉（假红），或反过来放过不逐字的命令（假绿）。

故本文件既锁"改坏必红"（变异验证，见下），也锁"反向正常流程不得红"（真实语料里的形态必须
判干净），并用合成坏响应逐条钉住 audit_stream 的判据极性，再加两条结构锁：探针**不得自开
HTTP 路径**（必须复用 localapi 的环回断言）、**不得打 /admin 之外的任何路径**——ADRs 0013
的红线对探针自己也有效，仓库里有过误调 /admin 触发真重灌的事故。

**变异验证实测记录（2026-10-10，本机 pytest；全套 73 用例）**——逐条真改真跑再复原，不是推论：
  ① `section_gate_ok` 改成恒真 → **8 failed / 65 passed**（test_section_gate 七个 False 参数
     + test_audit_stream_flags_a_write_operation_section）。
  ② `verbatim_ok` 改成恒真 → **4 failed / 69 passed**（三条逐字反向用例 + 合成幻觉命令那条）；
     "零幻觉"整条判据失效却没报错，正是它要防的第 1 类。
  ③ `redline_hits` 改成恒空 → **21 failed / 52 passed**（全部破坏性形态绊线 + 合成红线条）。
  ④ `closed_under_refs` 把 match 前置为 None → **5 failed / 68 passed**（含"干净契约也不该 FAIL"）。
  ⑤ AC-7 租户核对改成恒真 → **1 failed / 72 passed**（test_audit_stream_flags_a_foreign_tenant_chunk）；
     这是跨租户泄漏的唯一可执行锁，只有一条用例盯着它——所以这条用例绝不能删。
  复原后 73 passed；反向（正常流程）无一条红。

**一次活体抓到的真缺陷（已修）**：首跑 live 时 `jstack <pid> | grep -A 20 "ConsumeMessageThread"`
被判"重定向到文件"——绊线把 `<pid>` 的收尾尖括号当成了重定向符。判据与 Java `maskPlaceholders`
逐字对齐后修复（test_placeholders_are_not_redirects 锁住）。**这正是"绊线宁可错报也不漏报"的
代价所在**：错报会让 AC-5 变成另一种空转门闩（喊狼来了），所以反向用例一条都不能少。
"""
from __future__ import annotations

import ast
import json
import sys
from contextlib import contextmanager
from pathlib import Path

import pytest

OFFLINE = Path(__file__).resolve().parent.parent

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把某条不变量改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
sys.path.insert(0, str(OFFLINE))
import action_contract_probe as acp  # noqa: E402

# ── 取材真实语料形状的一段排查叶（契约逐字性的正样本） ──
CHUNK = """## 第二步：定位慢 SQL

打开慢查询日志，对疑似语句执行：

```sql
EXPLAIN SELECT * FROM orders \\
WHERE status = 'PENDING' \\
  AND created_at > NOW() - INTERVAL 1 HOUR;  -- 重点看 key 与 rows
```

看完记得关掉开关。
"""

SAFE_COMMANDS = [
    "docker logs --tail 200 pay-svc > /dev/null",
    "kubectl get pods -n prod | grep pay",
    "systemctl status pay-gateway",
    "mysql -e \"SHOW PROCESSLIST\" >&2",
    "cat /var/log/kill.log | tail -50",
    "jstack 12345 | grep -A 15 \"pool\"",
    "cat /proc/12345/status | grep VmRSS",
    "jstack <pid> | grep -A 20 \"ConsumeMessageThread\"",
    "kubectl logs <pod> -n prod --tail=200 > /dev/null",
]


# ---------------------------------------------------------------- 逐字性（AC-2）

def test_collapse_merges_continuations_and_folds_whitespace():
    raw = "EXPLAIN SELECT * FROM orders \\\n  WHERE id = 1;\n\nnext  line"
    assert acp.collapse(raw) == "EXPLAIN SELECT * FROM orders WHERE id = 1; next line"


def test_verbatim_accepts_a_joined_multiline_command_with_inline_comment():
    cmd = "EXPLAIN SELECT * FROM orders WHERE status = 'PENDING' AND created_at > NOW() - INTERVAL 1 HOUR;"
    assert acp.verbatim_ok(cmd, CHUNK)


def test_verbatim_rejects_a_hallucinated_command():
    """看起来很像、但原文里没有的命令必须判否——这是"零幻觉"判据的全部意义。"""
    assert not acp.verbatim_ok("EXPLAIN ANALYZE SELECT * FROM orders;", CHUNK)
    assert not acp.verbatim_ok("rm -rf /tmp/orders", CHUNK)


def test_verbatim_rejects_empty_needle():
    assert not acp.verbatim_ok("", CHUNK)
    assert not acp.verbatim_ok("   \n  ", CHUNK)


def test_verbatim_needs_raw_text_not_only_a_header():
    """chunk 只有标题没有正文时，命令不可能逐字——防止判据退化成"标题像就算过"。"""
    assert not acp.verbatim_ok("kubectl get pods", "## 排查步骤\n\n本步无命令。")


# ---------------------------------------------------------------- 段名闸（AC-4）

@pytest.mark.parametrize("breadcrumb,expect", [
    ("订单创建超时排查手册（50012） > 排查步骤 > 第二步：定位慢 SQL", True),
    ("订单创建超时排查手册（50012） > 排查步骤", True),
    ("Redis 超时排查手册 > 常见误判", False),
    ("Redis 超时排查手册 > 已知陷阱", False),
    ("Redis 超时排查手册 > 止损操作", False),
    ("Redis 超时排查手册 > 事故摘要", False),
    ("Redis 超时排查手册", False),                       # 只有一段：没有"段"
    ("Redis 超时排查手册 > 事故摘要 > 排查步骤", False),   # "排查"在第三段不算
    ("", False),
])
def test_section_gate(breadcrumb, expect):
    assert acp.section_gate_ok(breadcrumb) is expect


def test_step_of_uses_leaf_or_falls_back_to_section():
    assert acp.step_of("A > 排查步骤 > 第二步：定位慢 SQL") == "第二步：定位慢 SQL"
    assert acp.step_of("A > 排查步骤") == "排查步骤"
    assert acp.step_of("A") == ""


def test_blacklist_words_never_pass():
    for bc in ("A > 止损操作", "A > 修复步骤", "A > 处置手册", "A > 排查陷阱", "A > 排查常见误判"):
        assert not acp.blacklist_ok(bc)


# ---------------------------------------------------------------- 契约 ⊆ 引用（AC-3）

def _ref(**kw):
    base = {"chunkId": "rb-001::排查步骤 > 第二步", "breadcrumb": "手册 > 排查步骤 > 第二步：定位慢 SQL",
            "service": "pay-svc"}
    base.update(kw)
    return base


def _action(**kw):
    base = {"n": 1, "step": "第二步：定位慢 SQL", "command": "EXPLAIN SELECT 1;", "lang": "sql",
            "ref": "rb-001::排查步骤 > 第二步",
            "breadcrumb": "手册 > 排查步骤 > 第二步：定位慢 SQL", "service": "pay-svc"}
    base.update(kw)
    return base


def test_closed_under_refs_accepts_the_same_source():
    ok, why = acp.closed_under_refs(_action(), [_ref()])
    assert ok, why


def test_closed_under_refs_rejects_a_ref_outside_the_cited_set():
    ok, why = acp.closed_under_refs(_action(), [_ref(chunkId="rb-002::别的")])
    assert not ok and "不在本次引用集里" in why


def test_closed_under_refs_rejects_breadcrumb_drift():
    ok, why = acp.closed_under_refs(_action(breadcrumb="手册 > 排查步骤 > 第三步"),
                                    [_ref()])
    assert not ok and "breadcrumb" in why


def test_closed_under_refs_rejects_service_drift():
    ok, why = acp.closed_under_refs(_action(service="other-svc"), [_ref()])
    assert not ok and "service" in why


def test_closed_under_refs_rejects_step_not_from_the_cited_section():
    ok, why = acp.closed_under_refs(_action(step="第一步：查证书"), [_ref()])
    assert not ok and "step" in why


def test_numbering_is_contiguous_from_one():
    assert acp.numbering_ok([_action(n=1), _action(n=2, ref="x"), _action(n=3, ref="y")])
    assert acp.numbering_ok([])
    assert not acp.numbering_ok([_action(n=1), _action(n=3)])
    assert not acp.numbering_ok([_action(n=0)])


# ---------------------------------------------------------------- 红线绊线（AC-5）

@pytest.mark.parametrize("cmd", [
    "rm -rf /tmp/x",
    "sudo rm -rf /var/log/*.log",
    "sudo -u pay rm -rf /tmp/x",
    "mv a.log b.log",
    "dd if=/dev/zero of=/dev/sda",
    "kill -9 1234",
    "pkill -f pay-svc",
    "chmod 777 /etc/hosts",
    "kubectl delete pod pay-1",
    "docker rm -f $(docker ps -aq)",
    "git push --force origin main",
    "redis-cli FLUSHALL",
    "mysql -e \"DROP TABLE orders\"",
    "systemctl restart pay-gateway",
    "jstack 123 > /tmp/stack.txt",
    "jstack 123 >> /tmp/stack.txt",
    "jstack $(pgrep -f pay) > /tmp/out",
    "kubectl exec pay-1 -- rm -rf /tmp",
    "cat `ls /tmp`",
    "system(\"rm -rf /\")",
])
def test_redline_trips_on_destructive_forms(cmd):
    assert acp.redline_hits(cmd), f"该形态必须绊线：{cmd}"


@pytest.mark.parametrize("cmd", SAFE_COMMANDS)
def test_redline_stays_silent_on_read_only_forms(cmd):
    """反向：合法只读命令一条都不许绊线——绊线恒真会让 AC-5 变成另一种空转门闩。"""
    assert acp.redline_hits(cmd) == [], f"误伤只读命令：{cmd}"


def test_redline_ignores_destructive_words_in_arguments():
    assert acp.redline_hits("cat /var/log/kill.log") == []
    assert acp.redline_hits("grep -A 15 'pool' /tmp/jstack.out") == []


def test_redirect_to_dev_null_and_fd_is_allowed():
    assert not acp._redirect_to_file("docker logs --tail 200 pay-svc > /dev/null")
    assert not acp._redirect_to_file("echo x >&2")
    assert not acp._redirect_to_file("docker logs pay > /dev/stdout")
    assert not acp._redirect_to_file("echo 'a > b'")           # 引号内不算重定向
    assert acp._redirect_to_file("jstack 1 > /tmp/a.txt")


def test_placeholders_are_not_redirects():
    """`<pid>`/`<orderId>` 是占位符不是重定向——2026-10-10 首跑活体时这里误报过一次，
    把 `jstack <pid> | grep …` 当成落盘。判据与 maskPlaceholders 逐字对齐。"""
    assert not acp._redirect_to_file('jstack <pid> | grep -A 20 "ConsumeMessageThread"')
    assert not acp._redirect_to_file("kubectl logs <pod> -n prod")
    assert acp._redirect_to_file("cat < input.txt > out.txt")   # 真输入重定向 + 落盘输出


def test_impure_placeholder_keeps_its_redirect():
    """评审批 2026-10-11（F5）：掩码只认**纯 `<名>` 段**——`<in>out` 的 `>` 是真重定向，
    bash 语义下 stdin←in 且 stdout→out，真会把 out 落盘。掩码吞掉它=写面漏判。"""
    assert acp._pure_placeholder_end("grep x <in>out", 7) == -1
    assert ">" in acp._mask_placeholders("grep x <in>out")
    assert acp._redirect_to_file("grep x <in>out")
    # 纯占位符照旧掩码（不回退 2026-10-10 的活体回归锁）
    assert "<" not in acp._mask_placeholders("kubectl logs <pod> -n prod")
    assert "<" not in acp._mask_placeholders("top -H -p <pid> -n 1")
    assert not acp._redirect_to_file("top -H -p <pid> -n 1")


def test_process_substitution_is_rejected_like_command_substitution():
    """评审批 2026-10-11（F3）：`<(`/`>(`/`=(` 进程替换与 `$( ` 同罪——替换体里的命令会真实
    执行，argv 首词是 cat 也挡不住。标记表与 ReadOnlyCommandPolicy 同一份（Java+Python 双镜像）。"""
    for cmd in ("cat <(rm -rf /data)", "tar cf >(gzip > /tmp/x) /data", "diff <(sort a) <(sort b)"):
        assert "命令替换" in acp.redline_hits(cmd), cmd
    assert "命令替换" not in acp.redline_hits("jstack <pid> | grep -A 20 pool")


# ---------------------------------------------------------------- Prometheus（AC-8）

BODY = """# HELP aiops_guard_action_commands_rejected_total 判拒次数
# TYPE aiops_guard_action_commands_rejected_total counter
aiops_guard_action_commands_rejected_total 8.0
aiops_guard_redline_trips_total 0.0
"""


def test_prom_counter_reads_a_sample():
    assert acp.prom_counter(BODY, acp.PROM_NAME) == 8.0


def test_prom_counter_distinguishes_missing_from_zero():
    assert acp.prom_counter(BODY, "aiops_not_existing_total") is None
    assert acp.prom_counter("aiops_x_total 0.0\n", "aiops_x_total") == 0.0


def test_prom_counter_rejects_non_numeric():
    assert acp.prom_counter("aiops_x_total NaNish\n", "aiops_x_total") is None


# ---------------------------------------------------------------- 语料与租户前提（AC-7）

def test_tenant_has_fences_reflects_the_corpus_shape():
    corpus = {
        "a": {"text": "```bash\nls\n```", "breadcrumb": "d > 排查步骤", "tenant": "t1"},
        "b": {"text": "无围栏", "breadcrumb": "d > 排查步骤", "tenant": "t2"},
    }
    assert acp.tenant_has_fences(corpus, "t1")
    assert not acp.tenant_has_fences(corpus, "t2")
    assert not acp.tenant_has_fences(corpus, "t3")


def test_real_corpus_is_loadable_and_every_chunk_carries_a_tenant():
    """本地语料是 AC-7 的核对依据：读不到、或 chunk 缺 tenant，租户判据就悬空了。"""
    if not acp.CORPUS.exists():
        pytest.skip("语料不在（CI 未带离线语料）")
    corpus = acp.load_corpus()
    assert len(corpus) > 400
    assert all(c["tenant"] for c in corpus.values())


def test_real_corpus_tenant_split_is_the_one_the_probe_assumes():
    """探针的跨租户靶身份是 sre-acme/tenant-acme，其前提是该租户语料零围栏块
    （AC-7b 由此推出"契约必须空"）。语料若变了，这条会先红，逼人重读 AC-7b。"""
    if not acp.CORPUS.exists():
        pytest.skip("语料不在（CI 未带离线语料）")
    corpus = acp.load_corpus()
    tenants = {c["tenant"] for c in corpus.values()}
    assert "tenant-acme" in tenants and "tenant-internal" in tenants
    assert not acp.tenant_has_fences(corpus, "tenant-acme")


# ---------------------------------------------------------------- 结构锁：探针自己只读

def _probe_source() -> str:
    return acp.__file__ and Path(acp.__file__).read_text(encoding="utf-8")


# 探针唯一被允许经 localapi 发出的路径（都只读）。写死成白名单，而不是"不含 /admin"——
# 黑名单形态连"未来新增某个 /api/v1/admin/xxx"都要人想到才补，白名单默认拒。
READONLY_PATHS = ("/api/v1/auth/login", "/api/v1/copilot/chat/stream", "/actuator/prometheus")


def _localapi_path_literals() -> list[str]:
    """AST 里经 `localapi.xxx("...")` 发出的路径字面量（只看首参，绕开调用点的变量化写法）。"""
    tree = ast.parse(_probe_source())
    out = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                and isinstance(node.func.value, ast.Name) and node.func.value.id == "localapi":
            if node.args and isinstance(node.args[0], ast.Constant) \
                    and isinstance(node.args[0].value, str):
                out.append(node.args[0].value)
    return out


def test_probe_never_touches_any_admin_endpoint():
    """ADRs 0013 红线对探针自己同样有效；仓库里有过误调 /admin 触发真重灌的事故。
    用 AST 查字面量而不是全文 grep——否则 docstring 里"我们不打 /admin"会被当成打了。"""
    paths = _localapi_path_literals()
    assert paths, "没读到任何 localapi 路径字面量（探针退化成自开 HTTP 了？）"
    for p in paths:
        assert p in READONLY_PATHS, f"探针打了只读白名单之外的路径：{p}"
    assert not [p for p in paths if "admin" in p]


def test_probe_reuses_localapi_and_opens_no_own_http_path():
    """不自开连接路径：环回断言与"不跟随越界重定向"都只活在 localapi 一处。"""
    tree = ast.parse(_probe_source())
    imported = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(a.name.split(".")[0] for a in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module.split(".")[0])
    assert "urllib" not in imported and "requests" not in imported and "http" not in imported
    assert "localapi" in imported


def test_probe_declares_a_chat_budget_with_a_hard_cap():
    """LLM 成本护栏：探针必须有写死的预算，且小于等于 human-readable 的常量值。"""
    assert isinstance(acp.CHAT_BUDGET, int) and 0 < acp.CHAT_BUDGET <= 15
    src = _probe_source()
    assert "budget" in src and "已用尽" in src


def test_probe_queries_use_error_codes_that_exist_in_the_corpus():
    """探针形状必须用**语料里真实存在**的错误码——编一个不存在的码去测"落空"是自欺
    （曾这样把"语料里压根没有"误记成系统上限，见 opspilot-production-readiness）。"""
    if not acp.CORPUS.exists():
        pytest.skip("语料不在（CI 未带离线语料）")
    codes = set()
    for line in acp.CORPUS.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        meta = (json.loads(line).get("metadata") or {})
        codes.update(meta.get("error_codes") or [])
    assert codes, "语料里没读到任何错误码"
    for tag, query in acp.QUERIES + [("cross-tenant", acp.CROSS_TENANT_QUERY)]:
        found = [c for c in codes if c in query]
        assert found, f"形状 {tag} 用的错误码不在语料里：{query}"


# ---------------------------------------------------------------- 改坏必红：audit_stream 的判据方向
# 上面全是正向用例（干净输入→PASS）。这一组喂**坏输入**，逐条钉住每条判据的极性——
# 否则"段名闸恒真""逐字恒真""红线恒真"这类空转门闩能一路绿到线上。

CORPUS_FAKE = {
    "c1": {"text": "```sql\nSHOW FULL PROCESSLIST;\n```\n",
           "breadcrumb": "排查手册 > 排查步骤 > 第二步：定位慢 SQL", "tenant": "tenant-internal"},
    # 逐字含破坏性命令的 chunk：用来把 AC-2 与 AC-5 解耦（只有 AC-5 该红）
    "c2": {"text": "```bash\nkubectl delete pod pay-1 -n prod\n```\n",
           "breadcrumb": "排查手册 > 排查步骤 > 第三步：清理", "tenant": "tenant-internal"},
    "c3": {"text": "```bash\nls /var/log\n```\n",
           "breadcrumb": "他租户手册 > 排查步骤 > 第一步", "tenant": "tenant-other"},
}
REFS_FAKE = [{"chunkId": "c1", "breadcrumb": "排查手册 > 排查步骤 > 第二步：定位慢 SQL",
              "service": "pay-svc"}]


def _contract(**kw):
    base = {"n": 1, "step": "第二步：定位慢 SQL", "command": "SHOW FULL PROCESSLIST;",
            "lang": "sql", "ref": "c1",
            "breadcrumb": "排查手册 > 排查步骤 > 第二步：定位慢 SQL", "service": "pay-svc"}
    base.update(kw)
    return base


def _stream_fixture(actions=None, *, with_actions_key=True, refs=None):
    done = {"ttft_ms": 12, "refs": REFS_FAKE if refs is None else refs}
    if with_actions_key:
        done["actions"] = [] if actions is None else actions
    return {"status": 200, "error": None, "done": done, "meta": {"cache_hit": "none"}}


@contextmanager
def _capture_results():
    """把探针模块级的 results 换成临时列表：audit_stream 直接往它里面记 PASS/FAIL。"""
    saved = acp.results
    acp.results = []
    try:
        yield acp.results
    finally:
        acp.results = saved


def _failing(out, name) -> list[str]:
    return [d for n, ok, d in out if not ok and n == name]


def test_audit_stream_accepts_a_clean_contract():
    with _capture_results() as out:
        acp.audit_stream("T", _stream_fixture([_contract()]), CORPUS_FAKE, "tenant-internal")
    assert not [n for n, ok, _ in out if not ok], "干净契约不该有任何 FAIL"


def test_audit_stream_flags_a_missing_actions_key():
    with _capture_results() as out:
        acp.audit_stream("T", _stream_fixture(with_actions_key=False), CORPUS_FAKE,
                         "tenant-internal")
    assert _failing(out, "T-ac1-done-actions-key")


def test_audit_stream_flags_a_hallucinated_command():
    with _capture_results() as out:
        acp.audit_stream("T", _stream_fixture([_contract(command="rm -rf /")]), CORPUS_FAKE,
                         "tenant-internal")
    assert _failing(out, "T-ac2-verbatim-0"), "不在原文里的命令必须判幻觉"


def test_audit_stream_flags_an_action_outside_the_cited_refs():
    with _capture_results() as out:
        acp.audit_stream("T", _stream_fixture([_contract(ref="c9")], refs=REFS_FAKE),
                         CORPUS_FAKE, "tenant-internal")
    assert _failing(out, "T-ac3-closure-0"), "契约越出引用集必须判红"


def test_audit_stream_flags_a_write_operation_section():
    """段名闸的极性：面包屑第二段不含"排查"时必须红（否则黑名单段会混进契约）。"""
    with _capture_results() as out:
        acp.audit_stream("T", _stream_fixture([_contract(breadcrumb="排查手册 > 止损操作",
                                                         step="止损操作")]),
                         CORPUS_FAKE, "tenant-internal")
    assert _failing(out, "T-ac4-section-gate-0")
    assert _failing(out, "T-ac4-blacklist-0")


def test_audit_stream_flags_a_destructive_command_that_is_verbatim():
    with _capture_results() as out:
        acp.audit_stream(
            "T",
            _stream_fixture([_contract(ref="c2", command="kubectl delete pod pay-1 -n prod",
                                       step="第三步：清理",
                                       breadcrumb="排查手册 > 排查步骤 > 第三步：清理")],
                            refs=[{"chunkId": "c2",
                                   "breadcrumb": "排查手册 > 排查步骤 > 第三步：清理",
                                   "service": "pay-svc"}]),
            CORPUS_FAKE, "tenant-internal")
    assert not _failing(out, "T-ac2-verbatim-0"), "这条是逐字的，AC-2 不该红"
    assert _failing(out, "T-ac5-redline-0"), "破坏性命令必须绊线（即便它逐字）"


def test_audit_stream_flags_a_foreign_tenant_chunk():
    with _capture_results() as out:
        acp.audit_stream(
            "T",
            _stream_fixture([_contract(ref="c3", command="ls /var/log", step="第一步",
                                       breadcrumb="他租户手册 > 排查步骤 > 第一步",
                                       service="other")],
                            refs=[{"chunkId": "c3",
                                   "breadcrumb": "他租户手册 > 排查步骤 > 第一步",
                                   "service": "other"}]),
            CORPUS_FAKE, "tenant-internal")
    assert _failing(out, "T-ac7-ref-tenant-0"), "引用了别家租户的 chunk 必须判红"


def test_audit_stream_flags_a_contract_from_a_fence_free_tenant():
    """AC-7b 的极性：某租户语料零围栏块 ⇒ 契约必须空；非空即跨租户泄漏（构造上不可能自产）。
    合成语料：命令在原文里（AC-2 过得去）但该租户没有围栏块——真实抽取器不可能产出它。"""
    corpus = {"c9": {"text": "SHOW FULL PROCESSLIST;",
                     "breadcrumb": "他租户手册 > 排查步骤", "tenant": "tenant-other"}}
    refs = [{"chunkId": "c9", "breadcrumb": "他租户手册 > 排查步骤", "service": "pay-svc"}]
    with _capture_results() as out:
        acp.audit_stream("T", _stream_fixture([_contract(ref="c9", step="排查步骤",
                                                         breadcrumb="他租户手册 > 排查步骤")],
                                              refs=refs), corpus, "tenant-other")
    assert not _failing(out, "T-ac2-verbatim-0"), "合成 chunk 里确有这条命令"
    assert _failing(out, "T-ac7-tenant-empty-contract")


def test_audit_stream_reports_an_error_frame_as_failure():
    with _capture_results() as out:
        acp.audit_stream("T", {"status": 500, "error": {"code": "LLM_TIMEOUT"}, "done": {}},
                         CORPUS_FAKE, "tenant-internal")
    assert _failing(out, "T-stream")
