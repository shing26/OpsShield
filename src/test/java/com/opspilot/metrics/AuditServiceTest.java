package com.opspilot.metrics;

import com.opspilot.auth.UserContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ops Console 数据源的进程内事件环（ADR-0009）：有界、游标不重不漏、
 * 轮转/重启的丢失必须 truncated 显式告知——禁止静默空洞（外审①）。
 */
class AuditServiceTest {

    private static final UserContext U = new UserContext("sre-x", "sre", 1, "tenant-demo");

    private static void writeN(AuditService svc, int n) {
        for (int i = 0; i < n; i++) {
            svc.log(U, "chat", "sse", "manual", "q" + i, "fp", "none", "hybrid", false, 1, i, null, null, null, null);
        }
    }

    @Test
    void ringIsBoundedAt200WithMonotonicSeq() {
        AuditService svc = new AuditService();
        writeN(svc, 300);
        var r = svc.recentSince(0, 500);
        assertTrue(r.events().size() <= 200, "有界 200：实际 " + r.events().size());
        assertEquals(300, r.maxSeq());
        long prev = 0;
        for (var ev : r.events()) {
            long s = ((Number) ev.get("seq")).longValue();
            assertTrue(s > prev, "seq 必须严格递增");
            prev = s;
        }
    }

    @Test
    void cursorIsInclusiveExclusiveWithNoGapsNoDupes() {
        AuditService svc = new AuditService();
        writeN(svc, 50);
        var page1 = svc.recentSince(0, 30);
        assertEquals(30, page1.events().size());
        long c1 = ((Number) page1.events().get(29).get("seq")).longValue();
        var page2 = svc.recentSince(c1, 30);
        assertEquals(20, page2.events().size(), "不重不漏：第二页恰为剩余 20 条");
        assertTrue(page2.events().stream().allMatch(e ->
                ((Number) e.get("seq")).longValue() > c1));
        assertTrue(svc.recentSince(50, 50).events().isEmpty(), "追平后增量为空");
    }

    /** W8：落后游标穿越轮转边界 → truncated=true（前端据此插提示行并跳 maxSeq）。 */
    @Test
    void evictedGapIsNeverSilent() {
        AuditService svc = new AuditService();
        writeN(svc, 300);                       // 1..100 已驱逐，驻留 101..300
        var r = svc.recentSince(1, 200);
        assertTrue(r.truncated(), "游标落在已驱逐区必须报警");
        assertEquals(101, ((Number) r.events().get(0).get("seq")).longValue());

        assertFalse(svc.recentSince(100, 200).truncated(), "游标恰在最老驻留前一位=无丢失");
        assertFalse(svc.recentSince(299, 200).truncated());
    }

    @Test
    void serverRestartRollsCursorForward() {
        AuditService svc = new AuditService();
        writeN(svc, 5);
        var r = svc.recentSince(9999, 50);      // 客户端拿着重启前的旧游标
        assertTrue(r.truncated(), "游标超前=服务重启，同样显式告知");
        assertEquals(5, r.events().size(), "重启后给全部现存事件，游标不得卡死");
        assertEquals(5, r.maxSeq());
    }

    @Test
    void limitIsClampedAndEmptyStateIsSafe() {
        AuditService svc = new AuditService();
        var empty = svc.recentSince(0, 50);
        assertTrue(empty.events().isEmpty() && !empty.truncated() && empty.maxSeq() == 0);
        writeN(svc, 10);
        assertEquals(1, svc.recentSince(0, 0).events().size(), "limit 下限夹到 1");
    }

    /** H2：审计行携带请求级关联 id（MDC 缺席时不落字段——系统内部触发无 id 可言）。 */
    @Test
    void auditRowCarriesRequestIdFromMdc() {
        AuditService svc = new AuditService();
        org.slf4j.MDC.put("request_id", "abc12345");
        try {
            svc.log(U, "chat", "sse", "manual", "q", "fp", "none", "hybrid", false, 1, 1, null, null, null, null);
        } finally {
            org.slf4j.MDC.remove("request_id");
        }
        svc.log(U, "chat", "sse", "manual", "q2", "fp", "none", "hybrid", false, 1, 2, null, null, null, null);
        var r = svc.recentSince(0, 10);
        assertEquals("abc12345", r.events().get(0).get("request_id"), "MDC 在场→行携带 id");
        assertFalse(r.events().get(1).containsKey("request_id"), "MDC 缺席→无该字段");
    }

    /**
     * OP-A7：调用方关联键与 request_id **并存且互不覆盖**（ADR-0007 修订注）。
     * 前者=调用链（跨多步调用整段取出），后者=单次请求回查（服务端权威，不因调用方输入而变）。
     */
    @Test
    void auditRowCarriesCallerTraceIdAlongsideServerRequestId() {
        AuditService svc = new AuditService();
        org.slf4j.MDC.put("request_id", "srv00001");
        org.slf4j.MDC.put("trace_id", "agent-run-7f3a");
        try {
            svc.log(U, "chat", "sse", "manual", "q", "fp", "none", "hybrid", false, 1, 1, null, null, null, null);
        } finally {
            org.slf4j.MDC.remove("request_id");
            org.slf4j.MDC.remove("trace_id");
        }
        svc.log(U, "chat", "sse", "manual", "q2", "fp", "none", "hybrid", false, 1, 2, null, null, null, null);

        var r = svc.recentSince(0, 10);
        assertEquals("srv00001", r.events().get(0).get("request_id"));
        assertEquals("agent-run-7f3a", r.events().get(0).get("trace_id"), "调用链身份必须落盘");
        assertFalse(r.events().get(1).containsKey("trace_id"), "调用方没给就不落字段（不编造）");
    }

    /**
     * 告警来源可辨识（2026-09-16 自举告警源配套）：source 与 via 正交，必须是一等审计事实——
     * 否则"这条请求是系统自诊断发的还是人发的"在日志里答不出来，闭环叙事就没有可核验面。
     */
    @Test
    void auditRowCarriesSourceAndBlankNormalizesToManual() {
        AuditService svc = new AuditService();
        svc.log(U, "chat", "sse", "alert", "q", "fp", "none", "hybrid", false, 1, 1, null, null, null, null);
        svc.log(U, "chat", "sse", "   ", "q2", "fp", "none", "hybrid", false, 1, 2, null, null, null, null);
        var r = svc.recentSince(0, 10);
        assertEquals("alert", r.events().get(0).get("source"), "告警请求必须可在审计中辨识");
        assertEquals("manual", r.events().get(1).get("source"),
                "空白来源归一为 manual——审计行不留空，避免'没来源'与'人工'两种含义混淆");
    }

    /**
     * G2：`stage_ms` 仅在携带时落字段。**null ≠ 全 0**——null 是"非 chat 路径根本没测"
     * （/search、鉴权、管理面），全 0 是"测了且各段确实为 0"。混同会让运维把"没测"读成"很快"。
     */
    @Test
    void auditRowCarriesStageTimingsOnlyWhenPresent() {
        AuditService svc = new AuditService();
        svc.log(U, "chat", "sse", "manual", "q", "fp", "none", "hybrid", false, 1, 1, null, null,
                new StageTimings(100, 11, 22, 33, 0, 40, 50, 60), null);
        svc.log(U, "search", "search-api", "manual", "q2", "fp", "none", "hybrid", false, 1, 2,
                null, null, null, null);
        var r = svc.recentSince(0, 10);

        @SuppressWarnings("unchecked")
        var stages = (java.util.Map<String, Object>) r.events().get(0).get("stage_ms");
        assertEquals(8, stages.size(), "chat 行必须携带完整 stage_ms: " + stages.keySet());
        assertEquals(0, stages.get("rerank"), "0 值段仍落字段（未调用≠没测）");
        assertFalse(r.events().get(1).containsKey("stage_ms"), "非 chat 路径（传 null）不得落该字段");
    }

    /**
     * OP-A5：`degrade_level` 仅在观察过档位的路径上落字段。
     *
     * 为什么必须有这一列：`mode` 有两个来源（L1 降级 / 检索腿超时）都写 `es_only`，"这次是不是
     * 负载触发的降级"从 `mode` 单字段答不出来——2026-09-28 复核审计断言时实测的判据缺口。
     * 而 `/search` 不查状态机，传 null（"没观察"≠"观察到 L0"），故不得落字段。
     */
    @Test
    void auditRowCarriesDegradeLevelOnlyWhenObserved() {
        AuditService svc = new AuditService();
        svc.log(U, "chat", "sse", "manual", "q", "fp", "none", "es_only", false, 1, 1, null, null, null, "L1");
        svc.log(U, "search", "search-api", "manual", "q2", "fp", "none", "hybrid", false, 1, 2, null, null, null, null);
        var r = svc.recentSince(0, 10);
        assertEquals("L1", r.events().get(0).get("degrade_level"), "chat 行必须显式带档位");
        assertFalse(r.events().get(1).containsKey("degrade_level"), "未观察档位的路径不得落该字段");
    }

    /**
     * OP-A5：档位转移事件。`from/to/cause` 三者缺一即不可复核——离线时间线（读 logs/audit.jsonl）
     * 与压测结论都建立在这三个字段上，故锁死形状；`cause` 是有限词表（回归锁随状态机同步更新）。
     */
    @Test
    void degradeTransitionEventCarriesFromToAndCause() {
        AuditService svc = new AuditService();
        svc.logDegradeTransition("L0", "L1", "inflight");
        svc.logDegradeTransition("L1", "L0", "load_subsided");

        var r = svc.recentSince(0, 10);
        var first = r.events().get(0);
        assertEquals("degrade_transition", first.get("ev"));
        assertEquals("L0", first.get("from"));
        assertEquals("L1", first.get("to"));
        assertEquals("inflight", first.get("cause"));
        assertEquals("L1", r.events().get(1).get("from"), "第二条的 from 必须是前一条的 to（链式可读）");
    }

    /**
     * 反馈事件（闭环前置）：`ev="feedback"` 必须带着**身份三元组 + fingerprint + verdict** 落盘。
     *
     * 为什么锁：反馈是"人 → 系统"的唯一人工信号入口，也是"复盘→知识回灌"在案债务的前置。
     * 若它落盘时丢了 fp 或 verdict，收下来的就是一堆无法归因的行——债务链断在这里而无人察觉。
     */
    @Test
    void feedbackEventCarriesIdentityFingerprintAndVerdict() {
        AuditService svc = new AuditService();
        svc.logFeedback(U, "fp-abc123", "down", "错误码是编的");
        svc.logFeedback(U, "fp-nonote", "up", null);          // 无 note：不得落 note 字段
        svc.logFeedback(U, "fp-blank", "down", "   ");        // 空白 note：同"无 note"处理

        var r = svc.recentSince(0, 10);
        var first = r.events().get(0);
        assertEquals("feedback", first.get("ev"));
        assertEquals("sre-x", first.get("sub"));
        assertEquals("tenant-demo", first.get("tenant"));
        assertEquals(1, first.get("level"));
        assertEquals("fp-abc123", first.get("fp"));
        assertEquals("down", first.get("verdict"));
        assertEquals("错误码是编的", first.get("note"));

        assertFalse(r.events().get(1).containsKey("note"), "无 note 不落字段");
        assertFalse(r.events().get(2).containsKey("note"), "空白 note 归一为不落字段");
    }

    /**
     * G0（2026-10-22）：失败请求必须有审计行。
     *
     * 为什么锁：审计此前只在成功/拒答/澄清路径写，"欠费、超时、后段异常"是证据黑洞。
     * 本用例锁 schema 的最小可归因集：ev/身份/来源/query 截断/异常类/截断后的 message/档位；
     * 并锁"空白 source 归一 manual"（与 chat 行同口径，防止失败行出现空来源）。
     */
    @Test
    void chatFailedEventCarriesIdentityErrorAndTruncation() {
        AuditService svc = new AuditService();
        String longQuery = "q".repeat(500);
        String longMessage = "m".repeat(500);
        svc.logChatFailed(U, "sse", "   ", longQuery, "fp-x",
                new IllegalStateException(longMessage), "L2");

        var ev = svc.recentSince(0, 10).events().get(0);
        assertEquals("chat_failed", ev.get("ev"));
        assertEquals("sse", ev.get("via"));
        assertEquals("manual", ev.get("source"), "空白 source 归一 manual（与 chat 行同口径）");
        assertEquals("sre-x", ev.get("sub"));
        assertEquals("tenant-demo", ev.get("tenant"));
        assertEquals(1, ev.get("level"));
        assertEquals(200, ((String) ev.get("q")).length(), "query 必须截断（禁止 10MB 审计行）");
        assertEquals("IllegalStateException", ev.get("err_class"));
        assertEquals(200, ((String) ev.get("err_msg")).length(), "异常 message 必须截断");
        assertEquals("L2", ev.get("degrade_level"));
        assertFalse(ev.containsKey("cache_hit"), "失败行没有缓存语义，不得塞默认值");
    }

    /**
     * G1 门禁 9：每行必须带 prev_hash/hash，且后一行 prev_hash = 前一行 hash。
     * 这不是"日志里多两个字段"——它是 scripts/verify_audit_chain.py 能证明"内容未被改"的唯一锚点。
     */
    @Test
    void auditRowsFormHashChain() {
        AuditService svc = new AuditService();
        writeN(svc, 2);
        var rows = svc.recentSince(0, 10).events();
        String firstHash = (String) rows.get(0).get("hash");
        assertEquals("GENESIS", rows.get(0).get("prev_hash"), "首行从 GENESIS 起链");
        assertNotNull(firstHash);
        assertEquals(64, firstHash.length(), "SHA-256 十六进制长度");
        assertEquals(firstHash, rows.get(1).get("prev_hash"), "后一行必须指向前一行 hash");
        assertNotEquals(firstHash, rows.get(1).get("hash"));
    }

    /** G0：所有入审计的身份/路径字段统一截断——此前 sub 不截断，未认证请求可写巨型审计行。 */
    @Test
    void longIdentityFieldsAreTruncatedAtSingleHelper() {
        AuditService svc = new AuditService();
        UserContext longUser = new UserContext("s".repeat(500), "sre", 1, "tenant-demo");
        svc.log(longUser, "chat", "sse", "manual", "q".repeat(500), "fp", "none", "hybrid",
                false, 1, 1, null, null, null, null);
        svc.logAuthDenied("x".repeat(500), "/api/v1/auth/login", "login_failed", "bad_credentials");

        var rows = svc.recentSince(0, 10).events();
        assertEquals(64, ((String) rows.get(0).get("sub")).length(), "chat 行 sub 截 64");
        assertEquals(200, ((String) rows.get(0).get("q")).length(), "chat 行 q 截 200");
        assertEquals(64, ((String) rows.get(1).get("sub")).length(), "auth 行 sub 截 64");
    }

    /** #32：动作类事件保留命令原文（上限 1024），其余事件仍按 200 截断。 */
    @Test
    void actionEventsKeepLongCommandWhileOthersStayAt200() {
        AuditService svc = new AuditService();
        String longCmd = "kubectl " + "x".repeat(600);
        svc.log(U, "action_proposed", "action-api", "manual", longCmd, null, null, "none",
                false, 0, 0, null, null, null, null);
        svc.log(U, "chat", "sse", "manual", longCmd, null, null, "hybrid",
                false, 0, 0, null, null, null, null);
        var evs = svc.recentSince(0, 10).events();
        String actionQ = evs.stream().filter(e -> "action_proposed".equals(e.get("ev")))
                .findFirst().orElseThrow().get("q").toString();
        String chatQ = evs.stream().filter(e -> "chat".equals(e.get("ev")))
                .findFirst().orElseThrow().get("q").toString();
        assertEquals(longCmd.length(), actionQ.length(), "action_* 命令原文不得被 200 截断");
        assertEquals(200, chatQ.length(), "其余事件仍按 200 控制单行体积");
    }
}