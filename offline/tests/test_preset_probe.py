# -*- coding: utf-8 -*-
r"""左栏预设探针的回归锁（`offline/preset_probe.py`）。

为什么这些用例必须存在：这份探针守的是页面对用户的**承诺**（预设点一下出结果、覆盖范围
是真实边界），而它的每条判据都有一条静默失效路径——不报错，只让人以为承诺还成立：

  1. **解析器静默返回空**——`PRESETS` 被改名/删掉后 `parse_presets` 若返回 `[]`，P-1 的
     两个「同源」断言会变成「0 个预设全部有期望」而**全绿**，后面 P-2 一个都不跑。
     故这里逐条钉住「找不到就抛」，并把「不许把 `CONTRAST_CASES` 认成 `PRESETS`」也锁住
     （高级演练用例刻意不进左栏，混进来会让 P-2 拿对照用例当预设核）。
  2. **判据极性反了**——`shape_ok` 的 exact/min 两种语义、`coverage_mismatch` 的单字段
     漂移，都必须逐条红；否则「端点算错」会变成恒真式。
  3. **对照真值自证**——`corpus_view` 若从端点回包取差集而非从语料取，断言就恒真
     （空转门闩），这里用合成语料把「真值只来自 metadata」钉死。

**不触网**：`localapi._open` 被 fixture 换成断言失败，需要网络语义的用例显式 monkeypatch。
这与 `test_alert_bridge.py` / `test_alert_producer.py` 是同一教训——文档承诺过的「不触网」
必须变成机制，否则某次重构就会让单测悄悄开始打真网关。
"""
from __future__ import annotations

import ast
import sys
from pathlib import Path

import pytest

OFFLINE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(OFFLINE))
import preset_probe as pp  # noqa: E402


@pytest.fixture(autouse=True)
def _no_real_network(monkeypatch):
    def _boom(req, timeout):
        raise AssertionError("单元用例不得发起真实网络调用：请 monkeypatch localapi.stream_chat/get_json")

    monkeypatch.setattr(pp.localapi, "_open", _boom)


# ── 1. 预设解析：找不到就抛，绝不静默返回空 ─────────────────────────────

def test_parse_presets_reads_the_live_page():
    """真页面必须解析出 4 条，且 query 与线上实测过的形状一致。"""
    presets = pp.parse_presets(pp.PAGE.read_text(encoding="utf-8"))
    assert len(presets) == 4
    assert [q for _, q in presets] == list(pp.EXPECT)
    assert all(lbl and q for lbl, q in presets)


def test_parse_presets_raises_when_array_is_absent():
    """数组被删/改名 ⇒ 抛。返回空表会让 P-1 变成『0 个预设全部有期望』的假绿。"""
    with pytest.raises(ValueError):
        pp.parse_presets("<script>const OTHER = [];</script>")


def test_parse_presets_raises_when_array_is_empty():
    """数组还在但被清空 ⇒ 同样必须抛，而不是放过。"""
    with pytest.raises(ValueError):
        pp.parse_presets("const PRESETS = [\n];\n")


def test_parse_presets_does_not_swallow_contrast_cases():
    """`CONTRAST_CASES` 紧跟其后且形状相同——绝不能把它认成 `PRESETS`。

    混进来会让 P-2 拿对照用例（跨身份/跨租户）当预设核，判据语义当场错位。
    """
    html = (
        "const PRESETS = [\n"
        "  { label: 'A', q: 'qa' },\n"
        "];\n\n"
        "const CONTRAST_CASES = [\n"
        "  { label: 'B', q: 'qb', b: 'x' },\n"
        "];\n"
    )
    assert pp.parse_presets(html) == [("A", "qa")]


# ── 2. 判据极性：exact / min 两种语义不能互相顶替 ────────────────────────

@pytest.mark.parametrize("kind,want,got,expect", [
    ("exact", 0, 0, True),
    ("exact", 0, 1, False),      # 「诚实的零命令」变多了 ⇒ 页面承诺变了
    ("exact", 3, 2, False),
    ("min", 1, 1, True),
    ("min", 1, 7, True),         # 产额波动**不得**假红（action_contract_probe 同一条取舍）
    ("min", 1, 0, False),        # 但「一条都没有」必须红
])
def test_shape_ok_polarity(kind, want, got, expect):
    assert pp.shape_ok(kind, want, got) is expect


# ── 3. 对照真值只来自语料 metadata（防自证式空转） ───────────────────────

def _chunk(service, tenant, level, codes=()):
    return {"metadata": {"service": service, "tenant": tenant, "auth_level": level,
                         "error_codes": list(codes)}}


CORPUS = [
    _chunk("svc-a", "t1", 1, ["E1"]),
    _chunk("svc-b", "t1", 3, ["E2"]),
    _chunk("svc-c", "t2", 1, ["E3"]),
]


def test_corpus_view_filters_by_tenant_and_level():
    got = pp.corpus_view(CORPUS, "t1", 1)
    assert got == {"chunks": 1, "services": ["svc-a"], "error_codes": ["E1"]}
    got3 = pp.corpus_view(CORPUS, "t1", 3)
    assert got3 == {"chunks": 2, "services": ["svc-a", "svc-b"], "error_codes": ["E1", "E2"]}
    assert pp.corpus_view(CORPUS, "t2", 3)["chunks"] == 1


def _payload(chunks, services, codes, counts=None):
    return {
        "chunks": chunks,
        "services": len(services) if counts is None else counts[0],
        "error_codes": len(codes) if counts is None else counts[1],
        "service_list": [{"service": s, "chunks": 1} for s in services],
        "error_code_list": [{"code": c, "chunks": 1} for c in codes],
    }


def test_coverage_mismatch_is_empty_on_agreement():
    truth = pp.corpus_view(CORPUS, "t1", 3)
    assert pp.coverage_mismatch(_payload(2, ["svc-a", "svc-b"], ["E1", "E2"]), truth) == ""


@pytest.mark.parametrize("payload", [
    _payload(99, ["svc-a", "svc-b"], ["E1", "E2"]),          # 块数漂了
    _payload(2, ["svc-a", "svc-c"], ["E1", "E2"]),           # 服务清单漂了（跨租户混入）
    _payload(2, ["svc-a", "svc-b"], ["E1"]),                 # 错误码少了
    _payload(2, ["svc-a", "svc-b"], ["E1", "E2"], counts=(9, 2)),   # 计数与清单不一致
])
def test_coverage_mismatch_flags_each_drift(payload):
    truth = pp.corpus_view(CORPUS, "t1", 3)
    assert pp.coverage_mismatch(payload, truth) != ""


# ── 4. 结构锁：探针自己恪守只读红线 ──────────────────────────────────────

def _code_string_literals(path: Path) -> list[str]:
    """取源码里的字符串字面量，**排除 docstring**——文档里提到 /admin 是为了说明不打它。"""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    docs = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            d = ast.get_docstring(node, clean=False)
            if d:
                docs.add(d)
    return [n.value for n in ast.walk(tree)
            if isinstance(n, ast.Constant) and isinstance(n.value, str) and n.value not in docs]


def test_probe_never_targets_admin_endpoints():
    """ADR-0013 对探针自己也有效：清缓存会改变被测系统状态，仓库里有过误调 /admin 的事故。"""
    hits = [s for s in _code_string_literals(Path(pp.__file__)) if "/admin" in s]
    assert not hits, f"探针出现 /admin 字面量：{hits}"


def test_probe_only_uses_localapi_for_http():
    """不许自开 HTTP 路径——环回白名单与不跟随越界重定向都在 localapi 里。"""
    text = Path(pp.__file__).read_text(encoding="utf-8")
    for banned in ("urllib.request", "http.client", "requests."):
        assert banned not in text


def test_probe_endpoint_allowlist_is_exactly_three():
    """只读面就是这三个端点：login / chat/stream / copilot/coverage。多一个都要显式过审。"""
    hits = [s for s in _code_string_literals(Path(pp.__file__)) if s.startswith("/api/")]
    assert hits == ["/api/v1/copilot/coverage"]
    assert "/api/v1/copilot/chat/stream" in Path(pp.localapi.__file__).read_text(encoding="utf-8")


def test_probe_corpus_path_matches_gateway_default():
    """对照真值必须读网关默认吃的那份语料，否则比对的是两个世界。"""
    assert pp.CORPUS == OFFLINE / "corpus" / "chunks.jsonl"
    rows = pp.load_corpus()
    assert len(rows) > 0
    assert all("tenant" in r["metadata"] and "auth_level" in r["metadata"] for r in rows)
