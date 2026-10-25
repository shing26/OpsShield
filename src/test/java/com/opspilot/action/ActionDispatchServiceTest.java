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
        assertEquals("bf017c5a50d6d0068d388410abcddc955dd2b8c394ba60db0fd2a4f757f5d591",
                dispatch.expectedSignature(key, "act_vector01", sha, "2026-10-25T12:00:00+00:00"));
    }

    @Test
    void confirmAutoDispatchesAndCallbackFinalizes() {
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1d::恢复动作", CMD);
        var s = ops.confirm(CONFIRMER, p.opId(), p.confirmToken());
        // confirm 成功 → 自动派发（webhook 已收到 202）
        assertEquals("dispatched", s.state());
        assertEquals(1, hits.get(), "webhook 应收到一次派发");
        String body = LastDispatch.body;
        assertTrue(body.contains(p.opId()) && body.contains(CMD), "派发体含 op 与命令");
        assertTrue(body.contains("\"hmac\":"), "派发体含 HMAC");
        // 执行侧回调 → executed（终态）
        assertTrue(dispatch.callback(p.opId(), "executed", "exit 0"));
        assertEquals("executed", ops.status(PROPOSER, p.opId()).state());
        // 终态不可再变：二次回调 409 语义（false）
        assertFalse(dispatch.callback(p.opId(), "failed", "again"));
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
