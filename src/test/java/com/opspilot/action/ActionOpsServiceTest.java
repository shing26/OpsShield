package com.opspilot.action;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired JdbcTemplate jdbc;
    @Autowired com.opspilot.metrics.AuditService audit;   // #32：审计终态断言

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

    // ── 密级闸（#17）：command 原文不得下发给低密级调用者 ─────────────────────
    // 每个用例用独立租户：本类共用一个 H2 内存库，混租户会让「列表条数」无法断言。

    private static UserContext user(String sub, int level, String tenant) {
        return new UserContext(sub, level >= 3 ? "platform" : "sre", level, tenant);
    }

    private String insertOp(String tenant, int authLevel, String state) {
        String opId = "act_lvl_" + System.nanoTime();
        jdbc.update("INSERT INTO action_ops (op_id, action_class, command, ref, breadcrumb, service, "
                        + "auth_level, tenant, proposed_by, state) VALUES (?,?,?,?,?,?,?,?,?,?)",
                opId, "L1-write-low", CMD, "rb-l1::恢复动作", "rb-l1 > 恢复", "order-service",
                authLevel, tenant, "sre-full", state);
        return opId;
    }

    /** 列表按密级过滤：L1 调用者不该看到 L3 的 op（返回体里带 command 原文）。 */
    @Test
    void listFiltersByCallerAuthLevel() {
        String tenant = "tenant-lvl-list";
        insertOp(tenant, 1, "proposed");
        insertOp(tenant, 3, "proposed");

        var low = ops.list(user("sre-limited", 1, tenant), "proposed");
        var high = ops.list(user("sre-platform", 3, tenant), "proposed");

        assertEquals(1, low.size(), "L1 调用者只能看到 auth_level<=1 的 op");
        assertEquals(2, high.size(), "L3 调用者看到同租户全部");
        assertTrue(low.stream().allMatch(o -> o.command() != null), "低密级侧拿到的命令来自它有权看的行");
    }

    /** 单条读取同样过密级闸；够密级的同租户用户不受影响。 */
    @Test
    void statusRejectsLowerLevelCaller() {
        String tenant = "tenant-lvl-status";
        String opId = insertOp(tenant, 3, "proposed");

        var e = assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.status(user("sre-limited", 1, tenant), opId));
        assertEquals("op_level_insufficient", e.stage);
        assertEquals("proposed", ops.status(user("sre-platform", 3, tenant), opId).state(),
                "同租户够密级仍可读");
    }

    /** 取消是写操作：低密级不能取消高密级的 frozen op（与 unfreeze 同一条判据）。 */
    @Test
    void cancelRejectsLowerLevelCaller() {
        String tenant = "tenant-lvl-cancel";
        String opId = insertOp(tenant, 3, "frozen");

        var e = assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.cancel(user("sre-limited", 1, tenant), opId));
        assertEquals("confirmer_not_authorized", e.stage);
        assertEquals("expired", ops.cancel(user("sre-platform", 3, tenant), opId).state(),
                "够密级的同租户用户可取消");
    }

    /** #32：未知 opId 是 404 语义（not_found），不是 queryForMap 抛出的 500。 */
    @Test
    void unknownOpIdIsNotFoundNotServerError() {
        var e = assertThrows(ActionOpsService.ActionRejectedException.class,
                () -> ops.status(PROPOSER, "act_00000000000000000000"));
        assertEquals("not_found", e.stage);
    }

    /** #32：审计链必须含派发与过期终态——只有 proposed/confirmed 时，回放看不出动作有没有真正发出。 */
    @Test
    void auditTrailCoversDispatchedAndExpired() {
        loadL1Chunk();
        var p = ops.propose(PROPOSER, "rb-l1::恢复动作", CMD);
        ops.confirm(CONFIRMER, p.opId(), p.confirmToken());
        var p2 = ops.propose(PROPOSER, "rb-l1::恢复动作", CMD);
        ops.expire(p2.opId());
        var events = audit.recentSince(0, 500).events().stream()
                .map(ev -> String.valueOf(ev.get("ev"))).toList();
        String state = ops.status(PROPOSER, p.opId()).state();
        assertTrue(events.contains("action_dispatched") || events.contains("action_not_dispatched"),
                "缺派发终态审计：" + events);
        assertEquals("dispatched".equals(state), events.contains("action_dispatched"),
                "审计不得声称已派发而真实状态是 " + state + "：" + events);
        assertTrue(events.contains("action_expired"), "缺 action_expired：" + events);
    }
}