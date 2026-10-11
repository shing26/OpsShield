# -*- coding: utf-8 -*-
"""只读行动契约的活体复核探针（ADRs 0017）——**探针自己也只读**。

网关按 ReadOnlyActionExtractor 从**本次实际引用的 top-k chunk** 里逐字抽只读命令，随 SSE
done 帧的 `actions` 下发（ChatOrchestrator 里抽取与 refs 同源）。单测已把抽取器的四条不变式
（段名闸 / 只读策略 / 逐字 / 覆盖上下限）锁在离线语料上；本探针把这些不变式搬到**线上流量**
验证，并补三件单测做不到的事：流式协议的帧形状、缓存回放的契约保真、跨租户不出界。

  AC-1 帧形状   done 帧必带 `actions` 键（空表合法，缺键不合法）
  AC-2 逐字     每条 command 是其 `ref` 指向的 chunk 原文的子串（零幻觉＝零新增信息面）
  AC-3 闭包     契约 ⊆ 引用：`ref` 命中本次 refs，且 breadcrumb/service 与该引用逐字同源，
                编号 1..N 连续（客户端按顺序读的契约）
  AC-4 段名闸   面包屑第二段含"排查"且不含"陷阱/误判"，另加一份写操作/警告段黑名单
  AC-5 红线     破坏性首 token / 变更型子命令 / 落盘重定向 / 命令替换
  AC-6 回放     同一查询再问一次：actions 与首次逐条相同，且第二次 cache_hit 非 none
  AC-7 租户     每条 action 与每条 ref 的 chunk 都属于调用方租户（本地语料 metadata 核对）；
                某租户语料若自带零围栏块，则该租户的契约必须为空——跨租户泄漏的可执行锁
  AC-8 指标     aiops_guard_action_commands_rejected_total 可解析且单调不减
  AC-9 覆盖     至少一条契约被产出；0 条时 AC-2~AC-5 一条都没行使，按 FAIL 报出（而非假绿）

**不锁单请求产额**：产额被 final-top-k 卡住，是 OPS §5 闹钟表上的产品触发线（真实使用被指认
才动），不是测试该强约束的东西——锁了会让探针随语料扰动假红。各形状产额按 INFO 打出来。

对本探针自己同样恪守 ADR-0013 的只读红线：
  * 只读端点：login、POST /copilot/chat/stream、GET /actuator/prometheus；
  * **不打任何 /admin/**——连 /admin/cache/flush 也不打：这里没有判据需要清缓存，而清缓存
    会改变被测系统状态。仓库里有过误调 /admin 触发真重灌的事故，不给这个口子留缝；
  * 语料原文从 offline/corpus/chunks.jsonl 本地只读（判"逐字"需要原文，done 帧只带 chunkId；
    网关默认就吃这份文件——IngestionRunner 的 `opspilot.chunks-path` 默认值）；
  * HTTP 复用 localapi（loopback-only 断言、不跟随越界重定向都在那儿），不自开连接路径；
  * 口令只从环境变量 DEMO_PASSWORD 读（localapi.login），不落字面量。

用法：`python offline/action_contract_probe.py`（前置：栈已起、.env 已 source）。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import localapi  # noqa: E402

HERE = Path(__file__).resolve().parent
CORPUS = HERE / "corpus" / "chunks.jsonl"

CHAT_BUDGET = 8                       # 计划内 6 次 + 2 余量；超了就是有人忘了删调试形状
PROM_NAME = "aiops_guard_action_commands_rejected_total"
SECTION_MARK = "排查"                  # 与 ReadOnlyActionExtractor 同源
SECTION_EXCLUDE = ("陷阱", "误判")
BLACKLIST_WORDS = ("止损", "修复", "处置", "误判", "陷阱")   # 与抽取器单测同一份

# 固定查询形状。错误码都是语料里真实存在的码（编一个不存在的码去测"落空"是自欺——
# 教训见 opspilot-production-readiness）。hit/miss 只是**已知形状标签，不作为断言**：
# 产额是产品触发线，锁进测试会让探针随 top-3 抖动假红。措辞固定勿随探针调参而改。
QUERIES = [
    ("slow-sql", "订单创建超时 50012_DB_TIMEOUT 定位慢 SQL"),
    ("cert-expiring", "证书过期 50111_CERT_EXPIRING 排查"),
    ("mq-lag", "mq 消费积压 50031_MQ_CONSUME_LAG 排查"),
    ("miss-deadlock", "数据库死锁 50013_DB_DEADLOCK 怎么排查"),
]

# 跨租户靶：用**内部租户也有**的错误码问外部租户身份——内部文档里同码手册带着围栏命令，
# 若契约泄漏，这一问就会把内部命令吐给 tenant-acme（其语料自带零围栏块，见 load_corpus）。
CROSS_TENANT_QUERY = "订单创建超时 50012_DB_TIMEOUT 定位慢 SQL"

# 账号 → 租户（来源：scripts/seed_demo_users.sh 的种子注释）。用于核对"契约不出租户"。
ACCOUNT_TENANT = {"sre_l1": "tenant-internal", "sre_l3": "tenant-internal",
                  "sre_acme": "tenant-acme"}

results: list[tuple[str, bool, str]] = []
chat_calls = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}")


def info(msg: str) -> None:
    print(f"INFO  {msg}")


# ────────────────────────── 纯函数（单测直接覆盖，不发请求） ──────────────────────────

def collapse(text: str) -> str:
    """折叠成可做子串比较的单行：先合续行（`\\`+换行），再把连续空白压成一个空格。

    与 ReadOnlyActionExtractorTest#normalizedSource 同一口径，但不剥注释行——这里对
    haystack 要求更严：命令应当连原文里的行内注释一起被"包住"。
    """
    return re.sub(r"\s+", " ", text.replace("\\\n", " ")).strip()


def verbatim_ok(command: str, chunk_text: str) -> bool:
    needle = collapse(command)
    return bool(needle) and needle in collapse(chunk_text)


def segments(breadcrumb: str) -> list[str]:
    return [p.strip() for p in breadcrumb.split(" > ")]


def section_gate_ok(breadcrumb: str) -> bool:
    """段名闸（sectionOf 的镜像）：第二段含"排查"、不含排除词、至少有第二段。"""
    parts = segments(breadcrumb)
    if len(parts) < 2 or SECTION_MARK not in parts[1]:
        return False
    return not any(w in parts[1] for w in SECTION_EXCLUDE)


def step_of(breadcrumb: str) -> str:
    """步骤名（stepOf 的镜像）：有第三段取末段，否则回落段名；不足两段返回空串。"""
    parts = segments(breadcrumb)
    if len(parts) < 2:
        return ""
    return parts[-1] if len(parts) >= 3 else parts[1]


def closed_under_refs(action: dict, refs: list[dict]) -> tuple[bool, str]:
    """契约 ⊆ 引用。三项在 ChatOrchestrator 里出自同一个 chunk（refs 与 actions 都由
    chunks 派生），所以"逐字相同"不是苛求，正是构造保证；不一致即两处走了不同来源。"""
    ref = action.get("ref")
    match = next((r for r in refs if r.get("chunkId") == ref), None)
    if match is None:
        return False, f"ref={ref!r} 不在本次引用集里（契约越出引用）"
    for field in ("breadcrumb", "service"):
        if action.get(field) != match.get(field):
            return False, f"{field} 与引用不一致：{action.get(field)!r} != {match.get(field)!r}"
    if action.get("step") != step_of(match.get("breadcrumb") or ""):
        return False, f"step 不是来源小节标题：{action.get('step')!r}"
    return True, ""


def numbering_ok(actions: list[dict]) -> bool:
    return [a.get("n") for a in actions] == list(range(1, len(actions) + 1))


def blacklist_ok(breadcrumb: str) -> bool:
    return not any(w in breadcrumb for w in BLACKLIST_WORDS)


# 红线绊线与 ReadOnlyCommandPolicy 的允许清单**角色不同**，不是它的副本：允许清单管准入
# （只有已知安全的首 token 才放行），绊线管末端（真出现这些形态＝准入被绕过了）。宁可错报
# 也不漏报，但按"段首 token"定位，免得把 `cat /var/log/kill.log` 这类合法只读命令当红线。
REDLINE_FIRST_TOKEN = {
    "rm", "mv", "cp", "dd", "mkfs", "kill", "pkill", "killall", "taskkill",
    "shutdown", "reboot", "truncate", "chmod", "chown", "tee", "ln",
}
# `sudo -u pay rm x` 这类包装形态：跳过前置包装词/旗标（连带 `-u`/`-g` 的参数）再取真首 token。
WRAPPER_TOKENS = {"sudo", "env", "command", "nohup", "times"}
FLAGS_WITH_VALUE = {"-u", "-U", "-g", "-G", "--user", "--group", "--uid", "--gid"}
REDLINE_SUBVERBS = [
    (re.compile(r"\bsystemctl\s+(restart|stop|reload|start|mask|disable)\b", re.I), "启停/托管服务"),
    (re.compile(r"\bservice\s+\S+\s+(restart|stop|reload)\b", re.I), "启停服务"),
    (re.compile(r"\bkubectl\s+(delete|apply|patch|scale|drain|cordon|replace|edit|exec)\b", re.I),
     "kubectl 变更集群/进容器"),
    (re.compile(r"\bdocker\s+(rm|rmi|stop|kill|prune|update|rename|exec)\b", re.I),
     "docker 变更容器/进容器"),
    (re.compile(r"\bgit\s+(push|reset|clean|force|filter-branch)\b", re.I), "git 变更历史"),
    (re.compile(r"\bredis-cli\b.*\b(flushall|flushdb)\b", re.I), "redis 清库"),
    (re.compile(r"\b(mysql|psql|mongosh)\b.*\b(drop|truncate)\b", re.I), "落库破坏语句"),
]
# 命令替换标记与 ReadOnlyCommandPolicy.SUBSTITUTION_MARKERS 同一份（$(…)/反引号/
# system(/popen(/exec(，外加 bash/zsh 进程替换 <(…)/=(…)
SUBSTITUTION_MARKERS = ("$(", "`", "<(", ">(", "=(", "system(", "popen(", "exec(")


def _first_token(segment: str) -> str:
    toks = segment.strip().split()
    i = 0
    while i < len(toks) and (toks[i] in WRAPPER_TOKENS or toks[i].startswith("-")):
        if toks[i] in FLAGS_WITH_VALUE:
            i += 1
        i += 1
    return toks[i].strip("()") if i < len(toks) else ""


def _mask_placeholders(text: str) -> str:
    """把 `<pid>`/`<orderId>` 这类占位符掩成等长填充（maskPlaceholders 的镜像）。

    判据与 Java 侧逐字相同（2026-10-11 评审批收紧）：**整个空白分隔段恰好是 `<名>`**
    ——名字只含字母/数字/下划线/点/横杠，且 `>` 后紧跟空白或串尾。`<in>out` 这种
    "`>` 后贴着字"的形态不是占位符：那个 `>` 必须留给重定向判据（bash 语义下它真会把
    out 落盘）。真输入重定向（`cat < f`）`<` 后有空白，不会被掩掉。
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        close = _pure_placeholder_end(text, i)
        if close > i:
            out.append("P" * (close - i + 1))
            i = close + 1
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def _pure_placeholder_end(text: str, start: int) -> int:
    """`start` 处的 `<` 是否起一个纯占位符段，是则返回其 `>` 的下标，否则 -1（Java 镜像）。"""
    if start + 1 >= len(text) or text[start] != "<":
        return -1
    k = start + 1
    while k < len(text):
        c = text[k]
        if c == ">":
            break
        if c.isspace() or not (c.isalnum() or c in "_.-"):
            return -1
        k += 1
    if k >= len(text) or k == start + 1:
        return -1            # 没配上 >，或空名字
    if k + 1 < len(text) and not text[k + 1].isspace():
        return -1            # > 后贴着字符：<in>out 形态，不掩
    return k


def _redirect_to_file(command: str) -> bool:
    """落盘重定向：只允许 /dev/null、/dev/stdout、/dev/stderr 与 `&`fd
    （与 redirectsReadOnly 同口径，含"引号内不算"）。"""
    scan = _mask_placeholders(command)
    quote = ""
    i, n = 0, len(scan)
    while i < n:
        ch = scan[i]
        if quote:
            if ch == quote:
                quote = ""
            i += 1
            continue
        if ch in ("'", '"'):
            quote = ch
            i += 1
            continue
        if ch != ">":
            i += 1
            continue
        j = i + 1
        while j < n and scan[j] == ">":
            j += 1                       # >>
        s = j
        while s < n and scan[s].isspace():
            s += 1
        e = s
        while e < n and not scan[e].isspace():
            e += 1
        target = scan[s:e]
        if not (target.startswith(("/dev/null", "/dev/stdout", "/dev/stderr", "&"))):
            return True
        i = e
    return False


def redline_hits(command: str) -> list[str]:
    hits: list[str] = []
    for seg in re.split(r"[;&|\n]+", command):
        tok = _first_token(seg)
        if tok in REDLINE_FIRST_TOKEN:
            hits.append(f"破坏性首 token：{tok}")
    for pat, label in REDLINE_SUBVERBS:
        if pat.search(command):
            hits.append(label)
    if _redirect_to_file(command):
        hits.append("重定向到文件（只允许 >/dev/null 或 >fd）")
    if any(m in command for m in SUBSTITUTION_MARKERS):
        hits.append("命令替换")
    return hits


def prom_counter(body: str, name: str) -> float | None:
    """Prometheus 文本格式里取一个计数器值；样本缺失返回 None（别把缺失当 0）。"""
    for line in body.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[0] == name:
            try:
                return float(parts[1])
            except ValueError:
                return None
    return None


def load_corpus() -> dict[str, dict[str, str]]:
    """chunkId → {text, breadcrumb, tenant}（本地只读）。网关默认就吃这份文件。"""
    out: dict[str, dict[str, str]] = {}
    with open(CORPUS, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            out[d["chunk_id"]] = {
                "text": d.get("text", ""),
                "breadcrumb": d.get("breadcrumb", ""),
                "tenant": (d.get("metadata") or {}).get("tenant", ""),
            }
    return out


def tenant_has_fences(corpus: dict[str, dict[str, str]], tenant: str) -> bool:
    """该租户语料里是否存在带围栏块的 chunk——没有则其行动契约恒空，"必须空"判据才成立。"""
    return any(c["tenant"] == tenant and "```" in c["text"] for c in corpus.values())


# ────────────────────────── 一次 live 响应的复核 ──────────────────────────

def audit_stream(tag: str, res: dict, corpus: dict[str, dict[str, str]],
                 asker_tenant: str) -> tuple[list[dict], list[dict]]:
    """跑 AC-1~AC-5 + AC-7，返回 (actions, refs)。不复核空契约的内容项（没有可复的）。"""
    refs = list((res.get("done") or {}).get("refs") or [])
    if res.get("error"):
        check(f"{tag}-stream", False, f"链路异常帧 code={res['error'].get('code')}")
        return [], refs
    check(f"{tag}-stream", res.get("status") == 200, f"http={res.get('status')}")

    done = res.get("done")
    if not isinstance(done, dict):
        check(f"{tag}-ac1-done-actions-key", False, "无 done 帧")
        return [], refs
    # AC-1：空表合法，缺键不合法（老缓存 JSON 反序列化后走 actionsOrEmpty，不该缺键）
    check(f"{tag}-ac1-done-actions-key", "actions" in done,
          f"actions={'[]' if not done.get('actions') else str(len(done['actions'])) + ' 条'}")
    actions = done.get("actions") or []

    # AC-7a：引用本身不出租户（契约 ⊆ 引用，故这条先立住，契约自然不出租户）
    for i, r in enumerate(refs):
        c = corpus.get(r.get("chunkId"))
        check(f"{tag}-ac7-ref-tenant-{i}",
              c is not None and c["tenant"] == asker_tenant,
              f"chunkId={r.get('chunkId')} tenant={None if c is None else c['tenant']}")
    if not tenant_has_fences(corpus, asker_tenant):
        # AC-7b：该租户语料零围栏块 ⇒ 契约必须为空；非空即跨租户泄漏（构造上不可能自产）
        check(f"{tag}-ac7-tenant-empty-contract", not actions,
              f"{asker_tenant} 语料无围栏块，却拿到 {len(actions)} 条契约")

    for i, a in enumerate(actions):
        cid = a.get("ref")
        c = corpus.get(cid)
        if c is None:
            check(f"{tag}-ac2-verbatim-{i}", False,
                  f"本地语料无 chunkId={cid}（网关是否用 opspilot.chunks-path 指向别的语料？）")
        else:
            check(f"{tag}-ac2-verbatim-{i}", verbatim_ok(a.get("command") or "", c["text"]),
                  f"ref={cid}")
        ok, why = closed_under_refs(a, refs)
        check(f"{tag}-ac3-closure-{i}", ok, why or f"ref={cid}")
        bc = a.get("breadcrumb") or ""
        check(f"{tag}-ac4-section-gate-{i}", section_gate_ok(bc), bc or "(空面包屑)")
        check(f"{tag}-ac4-blacklist-{i}", blacklist_ok(bc), bc)
        hits = redline_hits(a.get("command") or "")
        check(f"{tag}-ac5-redline-{i}", not hits, ";".join(hits) or a.get("command"))
    check(f"{tag}-ac3-numbering", numbering_ok(actions),
          "n=" + ",".join(str(a.get("n")) for a in actions))
    return actions, refs


class Chatter:
    """chat 调用闸：预算超了直接抛，绝不让探针悄悄打爆网关的 LLM 配额。"""

    def __init__(self, token: str, budget: int):
        self.token = token
        self.budget = budget
        self.used = 0

    def ask(self, query: str) -> dict:
        if self.used >= self.budget:
            raise RuntimeError(f"chat 预算 {self.budget} 已用尽（query={query!r}）——"
                               "要么删调试形状，要么显式抬 CHAT_BUDGET")
        self.used += 1
        return localapi.stream_chat({"query": query, "source": "manual"}, self.token)


def fetch_prom() -> str:
    return localapi.get_text("/actuator/prometheus")


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    global chat_calls
    try:
        corpus = load_corpus()
    except FileNotFoundError:
        check("corpus-readable", False, str(CORPUS))
        print("\n== 行动契约探针 0/1 PASS ==")
        return 1
    tokens = localapi.load_tokens()
    l3, acme = tokens["sre_l3"], tokens["sre_acme"]

    prom_before = fetch_prom()
    check("ac8-prom-scrape-before", PROM_NAME in prom_before, "抓 /actuator/prometheus")

    total_actions = 0
    rows: list[str] = []
    first_hit: tuple[str, list[dict]] | None = None

    # ── A. 契约不变式（内部租户；含一个已知落空形状走空表路径） ──
    chatter = Chatter(l3, CHAT_BUDGET)
    for tag, query in QUERIES:
        res = chatter.ask(query)
        chat_calls += 1
        actions, refs = audit_stream(f"A-{tag}", res, corpus, ACCOUNT_TENANT["sre_l3"])
        total_actions += len(actions)
        meta = res.get("meta") or {}
        rows.append(f"A-{tag:<14} actions={len(actions):<2} refs={len(refs)} "
                    f"cache={meta.get('cache_hit')} fast={meta.get('fast_path')}")
        if actions and first_hit is None:
            first_hit = (query, actions)

    # ── B. 缓存回放保真（同一 query 逐字重发，不加重试尾注——尾注会改缓存键，那就不是回放了） ──
    if first_hit is None:
        check("ac6-replay-equality", False, "A 组没有非空契约，无从复核回放（先看 A 组 FAIL 项）")
    else:
        q, first_actions = first_hit
        again = chatter.ask(q)
        chat_calls += 1
        replay_actions, _ = audit_stream(f"B-replay", again, corpus, ACCOUNT_TENANT["sre_l3"])
        check("ac6-replay-equality",
              json.dumps(replay_actions, sort_keys=True, ensure_ascii=False)
              == json.dumps(first_actions, sort_keys=True, ensure_ascii=False),
              f"{len(first_actions)} 条 vs {len(replay_actions)} 条，逐条比对")
        check("ac6-replay-cache-hit",
              (again.get("meta") or {}).get("cache_hit") not in (None, "none"),
              f"cache_hit={(again.get('meta') or {}).get('cache_hit')}")
        rows.append(f"B-replay{'':<10} actions={len(replay_actions):<2} "
                    f"cache={(again.get('meta') or {}).get('cache_hit')} fast="
                    f"{(again.get('meta') or {}).get('fast_path')}")

    # ── C. 跨租户：外部身份问内部也有的错误码，契约不得越界 ──
    cross = Chatter(acme, CHAT_BUDGET - chat_calls)
    res = cross.ask(CROSS_TENANT_QUERY)
    chat_calls += 1
    actions, refs = audit_stream("C-cross-tenant", res, corpus, ACCOUNT_TENANT["sre_acme"])
    total_actions += len(actions)
    meta = res.get("meta") or {}
    rows.append(f"C-cross-tenant  actions={len(actions):<2} refs={len(refs)} "
                f"cache={meta.get('cache_hit')} fast={meta.get('fast_path')}")

    prom_after = fetch_prom()
    before, after = prom_counter(prom_before, PROM_NAME), prom_counter(prom_after, PROM_NAME)
    check("ac8-prom-parseable", before is not None and after is not None,
          f"before={before} after={after}")
    check("ac8-prom-monotone", before is not None and after is not None and after >= before,
          f"{PROM_NAME}: {before} → {after}")

    # AC-9：0 条时 AC-2~AC-5 一条都没行使——按 FAIL 报出，不留假绿
    check("ac9-contract-produced", total_actions > 0,
          f"本轮 {len(QUERIES) + 2} 次实调共产出 {total_actions} 条契约"
          f"（0 条＝不变式未被行使，不是'没问题'）")

    for r in rows:
        info(r)

    fails = [n for n, ok, _ in results if not ok]
    print(f"\n== 行动契约探针 {len(results) - len(fails)}/{len(results)} PASS | "
          f"chat 实调 {chat_calls} 次（预算 {CHAT_BUDGET}） ==")
    if fails:
        print("失败项:", ", ".join(fails))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
