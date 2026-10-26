package com.opspilot.action;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.opspilot.auth.UserContext;
import com.opspilot.chunk.Chunk;
import com.opspilot.retrieval.InMemoryIndex;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** L1 签名派发：跨语言向量（Java 签 ↔ Python reference verifier 验）+ 全生命周期。 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "opspilot.l1.enabled=true",
        "opspilot.l1.allowed-targets=deployment/order-api,deployment/inventory-api",
        "OPSPILOT_L1_WEBHOOK_ALLOW_PRIVATE=true",   // 测试 webhook 是环回
        "spring.datasource.url=jdbc:h2:mem:actiondispatch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
class ActionDispatchServiceTest {

    static final UserContext PROPOSER = new UserContext("sre-full", "platform", 3, "tenant-internal");
    static final UserContext CONFIRMER = new UserContext("sre-manager", "platform", 3, "tenant-internal");
    static final String CMD = "kubectl rollout restart deployment/inventory-api -n prod";

    /** 测试 webhook（收下派发请求即 202）。 */
    static HttpServer server;
    static final AtomicInteger hits = new AtomicInteger();

    @BeforeAll
    static void startWebhook() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/exec", ex -> {
            hits.incrementAndGet();
            byte[] body = ex.getRequestBody().readAllBytes();
            LastDispatch.body = new String(body, StandardCharsets.UTF_8);
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stopWebhook() {
        if (server != null) {
            server.stop(0);
        }
    }

    static class LastDispatch { static volatile String body; }

    @DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "dispatchtest".repeat(4));
        r.add("OPSPILOT_DISPATCH_KEY", () -> "bd".repeat(32));   // 与 Python 向量同 key
        r.add("opspilot.l1.dispatch-webhook",
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/exec");
    }

    @Autowired ActionOpsService ops;
    @Autowired ActionDispatchService dispatch;
    @Autowired InMemoryIndex index;

    @Test
    void signatureMatchesPythonReferenceVerifierVector() {
        // 由 offline/redteam/verify/reference_verifier.py 生成的固定向量（跨语言契约钉死）：
        // key=bd*32, op=act_vector01, payload=CMD, expires=2026-10-25T12:00:00+00:00
        byte[] key = HexFormatCompat.hex("bd".repeat(32));
        String payload = "kubectl rollout restart deployment/order-api -n prod";
        String sha = dispatch.sha256Hex(payload);
        assertEquals("2ce168965f22f9b686b846830dd45b39d06db428f2f9dbd8a0c1a7d5629ac7e3", sha);
        assertEquals("f143da957ef3a4aefd7aa1844cae4f80d27ce1cd56b15763ffeeffc3a7f7abdb",
                dispatch.expectedSignature(key, "act_vector01", sha, "2026-10-25T12:00:00+00:00"));
    }

    @Test
    void confirmAutoDispatchesAndCallbackFinalizes() {
        int before = hits.get();                    // 静态计数器跨测试共享：断言用增量
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1d::恢复动作", CMD);
        var s = ops.confirm(CONFIRMER, p.opId(), p.confirmToken());
        // confirm 成功 → 自动派发（webhook 已收到 202）
        assertEquals("dispatched", s.state());
        assertEquals(before + 1, hits.get(), "webhook 应收到一次派发");
        String body = LastDispatch.body;
        assertTrue(body.contains(p.opId()) && body.contains(CMD), "派发体含 op 与命令");
        assertTrue(body.contains("\"hmac\":"), "派发体含 HMAC");
        // 执行侧回调 → executed（终态）
        assertTrue(dispatch.callback(p.opId(), "executed", "exit 0"));
        assertEquals("executed", ops.status(PROPOSER, p.opId()).state());
        // 终态不可再变：二次回调 409 语义（false）
        assertFalse(dispatch.callback(p.opId(), "failed", "again"));
    }

    @Test
    void callbackFreshnessReasonIsLayered() {
        long now = java.time.Instant.now().getEpochSecond();
        String nonce = "n-" + System.nanoTime();
        assertNull(dispatch.callbackFreshnessReason(now, nonce), "窗口内首见 nonce 应通过");
        assertEquals("nonce_reused", dispatch.callbackFreshnessReason(now, nonce),
                "同 nonce 二次必须拒且 reason 分层（红队用例 6 断言点）");
        assertEquals("ts_out_of_window",
                dispatch.callbackFreshnessReason(now - 400, "n-old-" + System.nanoTime()),
                "超 ±300s 必须拒且 reason 分层（红队用例 7 断言点）");
    }

    @Test
    void expiredConfirmedOpCannotDispatch() {
        loadL1Chunk();
        // 时间机器：confirm 时刻 + 16 分钟 > 15 分钟窗口 → 派发必须被拒并转 expired
        java.time.Clock real = dispatch.clock;
        try {
            var p = ops.propose(PROPOSER, "rb-l1d::恢复动作", CMD);
            // 先用真时钟完成 confirm（stampDispatchDeadline 用真时钟盖章）
            dispatch.clock = real;
            ops.confirm(CONFIRMER, p.opId(), p.confirmToken());
            assertEquals("dispatched", ops.status(PROPOSER, p.opId()).state(),
                    "webhook 在线时立即派发");
        } finally {
            dispatch.clock = real;
        }
        // 直接构造一个"confirmed 未派发"的 op 测期限：关掉派发再 confirm——
        // 简化路径：用新 op，把时钟拨到 16 分钟后 confirm 会因确认窗口(900s)过期而拒，
        // 所以期限测试走 dispatchConfirmed 的惰性检查分支：注入过期 deadline。
        var p2 = ops.propose(PROPOSER, "rb-l1d::恢复动作", CMD);
        org.springframework.jdbc.core.JdbcTemplate jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("UPDATE action_ops SET state='confirmed', dispatch_expires_at=? WHERE op_id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), p2.opId());
        dispatch.dispatchConfirmed(p2.opId());
        assertEquals("expired", ops.status(PROPOSER, p2.opId()).state(),
                "过期 confirmed 必须转 expired，不可派发（契约 3）");
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext ctx;

    /** 决断 1：nonce 占坑必须在验签之后——坏签名不消耗 nonce（防匿名 DoS 占坑表）。 */
    @Test
    void badSignatureDoesNotConsumeNonce() {
        var audit = ctx.getBean(com.opspilot.metrics.AuditService.class);
        var ctl = new ExecutorCallbackController(dispatch, audit,
                ctx.getBean(com.opspilot.metrics.L1Metrics.class));
        long now = java.time.Instant.now().getEpochSecond();
        String nonce = "pin-" + System.nanoTime();
        var resp = ctl.callback(
                new ExecutorCallbackController.CallbackReq("act_x", "executed", "d", now, nonce),
                "deadbeef");                                     // 坏签名
        assertEquals(401, resp.getStatusCode().value());
        assertNull(dispatch.callbackFreshnessReason(now, nonce),
                "坏签名请求不得消耗 nonce——同 nonce 首个合法请求仍应通过");
    }

    /** frozen 态：开关关闭时 confirmed 未派发 → frozen（不是 expired）；解冻/取消都有正路。 */
    @Test
    void killSwitchFreezesConfirmedOpsAndRecovers() {
        var jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var index = ctx.getBean(com.opspilot.retrieval.InMemoryIndex.class);
        var audit = ctx.getBean(com.opspilot.metrics.AuditService.class);
        var metrics = ctx.getBean(com.opspilot.metrics.L1Metrics.class);
        // 开关关闭的第二套服务（同一 DB）。索引走 ObjectProvider（P0 修复后生产构造的形态）：
        // live 形态下没有这个 bean，此处显式给一个「总是返回该索引」的 provider，保持 memory 语义。
        @SuppressWarnings("unchecked")
        var indexProvider = (org.springframework.beans.factory.ObjectProvider<InMemoryIndex>)
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(indexProvider.getIfAvailable()).thenReturn(index);
        var disabled = new ActionOpsService(jdbc, indexProvider, audit,
                new ActionDispatchService(jdbc, "bd".repeat(32), null, false), metrics, "");
        String opId = "act_frozen_" + System.nanoTime();
        jdbc.update("INSERT INTO action_ops (op_id, action_class, command, ref, breadcrumb, service, "
                + "auth_level, tenant, proposed_by, state) VALUES (?,?,?,?,?,?,?,?,?,?)",
                opId, "L1-write-low", CMD, "rb-l1d::恢复动作", "r", "inventory-service",
                3, "tenant-internal", "sre-full", "confirmed");
        disabled.freezeSweep();
        assertEquals("frozen", disabled.status(PROPOSER, opId).state(),
                "开关关闭 → confirmed 冻结（不是 expired）");
        // 解冻：开关重开后由人显式操作 → 重置期限并自动派发
        var st = ops.unfreeze(CONFIRMER, opId);
        assertTrue(java.util.List.of("dispatched", "executed").contains(st.state()),
                "解冻后应自动派发，实际=" + st.state());
        // 取消：frozen → expired
        String op2 = "act_frozen2_" + System.nanoTime();
        jdbc.update("INSERT INTO action_ops (op_id, action_class, command, ref, breadcrumb, service, "
                + "auth_level, tenant, proposed_by, state) VALUES (?,?,?,?,?,?,?,?,?,?)",
                op2, "L1-write-low", CMD, "rb-l1d::恢复动作", "r", "inventory-service",
                3, "tenant-internal", "sre-full", "frozen");
        assertEquals("expired", ops.cancel(CONFIRMER, op2).state(), "取消 → expired");
    }

    /** SSRF 最小防护（盲点 5）：私网/环回/链路本地默认拒；显式 allowlist 才放。 */
    @Test
    void webhookPrivateAddressesRejectedUnlessAllowed() {
        assertThrows(IllegalStateException.class,
                () -> ActionDispatchService.validateWebhookUrl("http://127.0.0.1:8080/exec", false));
        assertThrows(IllegalStateException.class,
                () -> ActionDispatchService.validateWebhookUrl("http://169.254.169.254/latest", false));
        assertThrows(IllegalStateException.class,
                () -> ActionDispatchService.validateWebhookUrl("http://10.1.2.3:8080/exec", false));
        assertThrows(IllegalStateException.class,
                () -> ActionDispatchService.validateWebhookUrl("ftp://example.com/x", false));
        assertEquals("http://127.0.0.1:8080/exec",
                ActionDispatchService.validateWebhookUrl("http://127.0.0.1:8080/exec", true),
                "显式放开后内网 webhook 可用");
        assertNull(ActionDispatchService.validateWebhookUrl("", false), "空 = 未配置");
    }

    /** 指标面：propose/confirm/dispatch/executed 在 Prometheus 计数上可见（告警基础）。 */
    @Test
    void l1MetricsIncrementOnLifecycle() {
        var reg = ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class);
        double before = reg.get("aiops.l1.proposed").tag("tenant", "tenant-internal")
                .counter().count();
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1d::恢复动作", CMD);
        assertEquals(before + 1,
                reg.get("aiops.l1.proposed").tag("tenant", "tenant-internal").counter().count(),
                "propose 指标必须 +1");
        ops.confirm(CONFIRMER, p.opId(), p.confirmToken());
        assertTrue(reg.get("aiops.l1.dispatched").tag("tenant", "tenant-internal")
                .tag("result", "ok").counter().count() >= 1, "派发成功指标可见");
    }

    /** 密钥轮换：新 key 签名、旧 key 在窗口内仍可验（回调不断流）。 */
    @Test
    void previousKeyStillVerifiesDuringRotation() throws Exception {
        var jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        byte[] prev = java.util.HexFormat.of().parseHex("cd".repeat(32));
        var f = java.nio.file.Files.createTempFile("prevkey", ".txt");
        java.nio.file.Files.writeString(f, java.util.HexFormat.of().formatHex(prev));
        var rotated = new ActionDispatchService(jdbc, "", "", f.toString(), null, true, true,
                ctx.getBean(com.opspilot.metrics.L1Metrics.class));
        String material = "callback:act_x:executed:1:n1";
        String prevSig = ActionDispatchService.hmac(prev, material);
        assertTrue(rotated.signatureMatches(prevSig, material), "旧 key 签名在窗口内必须通过");
        assertFalse(rotated.signatureMatches("00".repeat(32), material), "错 key 必须拒");
    }

    /** 裁决 1·A：callback 豁免 l1 开关——l1.enabled=false 时在途 dispatched 仍能落终态。 */
    @Test
    void callbackExemptFromL1Switch() {
        var jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        // 直接构造"开关关闭但 key 在"的派发服务（回调用），并把一个 op 置为 dispatched
        var disabled = new ActionDispatchService(jdbc, "bd".repeat(32), null, false);
        assertFalse(disabled.dispatchEnabled(), "发起入口全关");
        assertTrue(disabled.callbackEnabled(), "回调通道豁免开关");
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1d::恢复动作", CMD);
        jdbc.update("UPDATE action_ops SET state='dispatched' WHERE op_id=?", p.opId());
        assertTrue(disabled.callback(p.opId(), "executed", "exit 0"),
                "关闭开关后已 dispatched 的 op 仍能回调落 executed——收尾通道不被发起开关误伤");
    }

    /** 守卫顺序：自确认被拒后 op 停在 proposed（无 confirmed 事件、无状态写入）。 */
    @Test
    void selfConfirmRejectionWritesNoState() {
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1d::恢复动作", CMD);
        var e = assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.confirm(PROPOSER, p.opId(), p.confirmToken()));
        assertEquals("self_confirm", e.stage);
        assertEquals("proposed", ops.status(PROPOSER, p.opId()).state(),
                "身份守卫不写状态——被拒后仍是 proposed，可由合法确认人完成");
        // 正向对照：不同 principal 确认仍成功（通路活着，不是兜底 403）
        assertEquals("dispatched", ops.confirm(CONFIRMER, p.opId(), p.confirmToken()).state());
    }

    /** 决断 3：confirm 同步完成派发——返回态即终态，不存在"确认了但不知派没派"的中间态。 */
    @Test
    void confirmReturnStateIsTerminalNoInbetween() {
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1d::恢复动作", CMD);
        var st = ops.confirm(CONFIRMER, p.opId(), p.confirmToken());
        assertTrue(List.of("dispatched", "failed", "confirmed").contains(st.state()),
                "confirm 返回必须是三态之一（派发成功/派发失败/派发未启用），无中间态；实际=" + st.state());
    }

    private void loadL1Chunk() {
        Chunk.Metadata m = new Chunk.Metadata("inventory-service", null, null,
                List.of("50012_DB_TIMEOUT"), 3, "prod", "tenant-internal", "L1-write-low");
        Chunk c = new Chunk("rb-l1d::恢复动作", "rb-l1d", "runbook_step",
                "## 恢复动作\n```bash\n" + CMD + "\n```\n", "rb-l1d > 恢复", m);
        index.load(List.of(c), null);
    }

    static class HexFormatCompat {
        static byte[] hex(String h) { return java.util.HexFormat.of().parseHex(h); }
    }
}
