package com.opspilot.action;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import com.opspilot.auth.UserContext;
import com.opspilot.chunk.Chunk;
import com.opspilot.retrieval.InMemoryIndex;

import static org.junit.jupiter.api.Assertions.*;

/** L1 状态机守卫（ADR-0028 评审增补 1/2/3 的判定点）。 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "opspilot.l1.enabled=true",
        "opspilot.l1.allowed-targets=deployment/order-api,deployment/inventory-api",
        "spring.datasource.url=jdbc:h2:mem:actionops;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.clean-disabled=true"})
class ActionOpsServiceTest {

    /** 运行时拼接构造测试密钥（工具链会脱敏源码里的长密钥字面量）。 */
    @org.springframework.test.context.DynamicPropertySource
    static void jwtSecret(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "l1actionops".repeat(4));   // 44 chars >= 256bit
    }

    @Autowired ActionOpsService ops;
    @Autowired InMemoryIndex index;

    static final UserContext PROPOSER = new UserContext("sre-full", "platform", 3, "tenant-internal");
    static final UserContext CONFIRMER = new UserContext("sre-manager", "platform", 3, "tenant-internal");
    static final UserContext ACME = new UserContext("sre-acme", "sre", 3, "tenant-acme");
    static final String CMD = "kubectl rollout restart deployment/order-api -n prod";

    private void loadL1Chunk() {
        Chunk.Metadata m = new Chunk.Metadata("order-service", null, null,
                List.of("50012_DB_TIMEOUT"), 3, "prod", "tenant-internal", "L1-write-low");
        Chunk c = new Chunk("rb-l1::恢复动作", "rb-l1", "runbook_step",
                "## 恢复动作\n```bash\n" + CMD + "\n```\n", "rb-l1 > 恢复", m);
        index.load(List.of(c), null);
    }

    @Test
    void fullLifecycleProposeConfirm() {
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1::恢复动作", CMD);
        assertEquals("proposed", ops.status(PROPOSER, p.opId()).state());
        var s = ops.confirm(CONFIRMER, p.opId(), p.confirmToken());
        assertEquals("confirmed", s.state());
        assertEquals("sre-manager", s.confirmedBy());
        // token 一次性：同 token 二次确认必须被拒
        assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.confirm(CONFIRMER, p.opId(), p.confirmToken()));
    }

    @Test
    void selfConfirmIsHardRejected() {
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1::恢复动作", CMD);
        var e = assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.confirm(PROPOSER, p.opId(), p.confirmToken()));
        assertEquals("self_confirm", e.stage);
    }

    @Test
    void crossTenantConfirmRejected() {
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1::恢复动作", CMD);
        var e = assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.confirm(ACME, p.opId(), p.confirmToken()));
        assertEquals("confirmer_not_authorized", e.stage);
    }

    @Test
    void notVerbatimCommandRejectedAtPropose() {
        loadL1Chunk();
        var e = assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.propose(PROPOSER, "rb-l1::恢复动作",
                        "kubectl rollout restart deployment/evil -n prod"));
        assertEquals("not_verbatim", e.stage);   // 服务端重验逐字性，不信任客户端
    }

    /** 灰度白名单：不在 allowed-targets 的目标必须拒（空配置=全拒，fail-closed）。 */
    @Test
    void targetOutsideAllowlistRejected() {
        String evil = "kubectl rollout restart deployment/evil-svc -n prod";
        Chunk.Metadata m = new Chunk.Metadata("order-service", null, null,
                List.of("50012_DB_TIMEOUT"), 3, "prod", "tenant-internal", "L1-write-low");
        Chunk c = new Chunk("rb-evil::恢复动作", "rb-evil", "runbook_step",
                "## 恢复动作\n" + evil + "\n", "rb-evil > 恢复", m);
        index.load(List.of(c), null);
        var e = assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.propose(PROPOSER, "rb-evil::恢复动作", evil));
        assertEquals("target_not_allowed", e.stage);
    }

    @Test
    void listIsTenantScoped() {
        loadL1Chunk();
        ops.propose(PROPOSER, "rb-l1::恢复动作", CMD);
        var mine = ops.list(PROPOSER, "proposed");
        assertTrue(mine.stream().anyMatch(o -> CMD.equals(o.command())), "本租户应看到待确认 op");
        assertTrue(mine.stream().allMatch(o -> o.expiresAt() != null), "面板倒计时需要 expiresAt");
        assertTrue(ops.list(ACME, "proposed").isEmpty(), "跨租户列表必须为空");
    }

    @Test
    void badTokenRejected() {
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1::恢复动作", CMD);
        var e = assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.confirm(CONFIRMER, p.opId(), "deadbeef"));
        assertEquals("bad_token", e.stage);
    }
}
