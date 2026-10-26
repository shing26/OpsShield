package com.opspilot.action;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.opspilot.auth.UserContext;
import com.opspilot.metrics.AuditService;
import com.opspilot.retrieval.InMemoryIndex;

/**
 * L1 受控行动契约的状态机服务（ADR-0028 规格 §2/§4/§5）。
 *
 * <p><b>构造保证（不是过滤承诺）</b>：proposed → confirmed 的唯一推进路径是
 * {@link #confirm}——它要求 (a) 确认人与提案人<b>不同 principal</b>（防被注入的 Agent
 * 自提案自确认）；(b) 一次性 token 的哈希原子消费（防重放）；(c) 窗口内（默认 900s）；
 * (d) 确认人租户匹配且密级足够。四个条件缺一，状态机不动。
 *
 * <p><b>propose 的服务端复核</b>：绝不信任客户端——chunk 可见性（身份过滤内的 byId）、
 * chunk 显式声明 action_class=L1-write-low、命令逐字 ⊆ chunk 原文（ADR-0017 不变式
 * 在 L1 的服务端重验）、ControlledCommandPolicy 三关判定，任一不过即拒。
 */
@Service
public class ActionOpsService {

    private static final Logger log = LoggerFactory.getLogger(ActionOpsService.class);
    private static final SecureRandom RNG = new SecureRandom();
    static final long CONFIRM_WINDOW_S = 900;      // 规格 §2：15 分钟

    private final JdbcTemplate jdbc;
    /**
     * memory 模式的语料索引——**live 形态下这个 bean 不存在**（{@link InMemoryIndex} 带
     * {@code @ConditionalOnProperty(storage.mode=memory)}）。所以这里是软依赖：L1 提案在 live 下
     * 明确拒绝并说明原因，而不是让整个应用起不来（2026-10-26 修 P0：此前强依赖导致
     * {@code storage.mode=live} 启动即 {@code NoSuchBeanDefinitionException}）。
     */
    private final ObjectProvider<InMemoryIndex> indexProvider;
    private final AuditService audit;
    private final ActionDispatchService dispatch;   // confirm 成功后自动派发（未启用则停在 confirmed）
    private final com.opspilot.metrics.L1Metrics l1Metrics;

    /** 灰度目标白名单（2026-10-25 评裁决 4·A）：配置定义而非 K8s label——
     * 任何有 RBAC 的人都能打 label，白名单形同虚设；配置变更走 OpsShield 审计。
     * 空 = 全拒（fail-closed：写面灰度默认不许任何目标）。 */
    private final java.util.Set<String> allowedTargets;

    public ActionOpsService(JdbcTemplate jdbc, ObjectProvider<InMemoryIndex> indexProvider, AuditService audit,
                            ActionDispatchService dispatch,
                            com.opspilot.metrics.L1Metrics l1Metrics,
                            @Value("${opspilot.l1.allowed-targets:}") String allowedTargetsCsv) {
        this.l1Metrics = l1Metrics;
        this.jdbc = jdbc;
        this.indexProvider = indexProvider;
        this.audit = audit;
        this.dispatch = dispatch;
        this.allowedTargets = java.util.Arrays.stream(allowedTargetsCsv.split(","))
                .map(String::trim).filter(t -> !t.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record ProposeResult(String opId, String confirmToken, String expiresAt) {}
    public record StatusResult(String opId, String state, String command, String ref,
                               String proposedBy, String confirmedBy, String tenant,
                               String expiresAt) {}

    /** L1 提案：全部服务端复核通过后落库，返回一次性确认 token（明文只出现这一次）。 */
    public ProposeResult propose(UserContext proposer, String chunkId, String command) {
        if (!dispatch.l1Enabled()) {                 // 灰度总开关：红队全绿前任何环境不开
            reject(proposer, chunkId, "l1_disabled", "L1 未启用（opspilot.l1.enabled）");
        }
        if (proposer.authLevel() < 2) {
            reject(proposer, chunkId, "proposer_level", "提案需 level>=2");
        }
        InMemoryIndex index = indexProvider.getIfAvailable();
        if (index == null) {                    // live 形态：明确拒绝，不静默降级成「chunk 不存在」
            reject(proposer, chunkId, "l1_requires_memory_storage",
                    "L1 提案需要 memory 存储形态（opspilot.storage.mode=memory）：live 的 ES byId 通道尚未接入");
        }
        InMemoryIndex.Doc doc = index.byId(chunkId, proposer.tenantId(), proposer.authLevel());
        if (doc == null) {
            reject(proposer, chunkId, "chunk_not_visible", "chunk 不存在或不可见");
        }
        String text = doc.chunk().text();
        if (!containsVerbatim(text, command)) {
            reject(proposer, chunkId, "not_verbatim", "命令不是 chunk 原文的逐字内容");
        }
        boolean declaresL1 = "L1-write-low".equals(doc.actionClass());
        ControlledCommandPolicy.Verdict verdict = ControlledCommandPolicy.classify(command, declaresL1);
        if (verdict != ControlledCommandPolicy.Verdict.L1_WRITE_LOW) {
            reject(proposer, chunkId, "policy_" + verdict.name().toLowerCase(Locale.ROOT),
                    "判定为 " + verdict + "，非 L1-write-low");
        }
        String target = ControlledCommandPolicy.targetOf(command);
        if (target == null || !allowedTargets.contains(target)) {
            reject(proposer, chunkId, "target_not_allowed",
                    "目标 " + target + " 不在灰度白名单（opspilot.l1.allowed-targets）");
        }
        String opId = "act_" + HexFormat.of().formatHex(random(10));
        byte[] tokenBytes = random(32);
        String token = HexFormat.of().formatHex(tokenBytes);
        Instant expires = Instant.now().plusSeconds(CONFIRM_WINDOW_S);
        jdbc.update("INSERT INTO action_ops (op_id, action_class, command, ref, breadcrumb, service, "
                + "auth_level, tenant, proposed_by, proposed_at, state, confirm_token_hash, confirm_expires_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                opId, "L1-write-low", command, chunkId, doc.chunk().breadcrumb(),
                doc.chunk().service(), doc.chunk().authLevel(), proposer.tenantId(),
                proposer.sub(), java.sql.Timestamp.from(Instant.now()), "proposed",
                sha256Hex(token), java.sql.Timestamp.from(expires));
        auditEvent(proposer, "action_proposed", opId + " " + command);
        l1Metrics.proposed(proposer.tenantId());
        return new ProposeResult(opId, token, expires.toString());
    }

    /**
     * L1 确认（面板已认证会话内点击）：confirmer ≠ proposer 是硬守卫（构造保证的核心）。
     * token 一次性：哈希校验通过后立刻置 NULL——同 token 二次确认必然失败。
     */
    public StatusResult confirm(UserContext confirmer, String opId, String token) {
        if (!dispatch.l1Enabled()) {                 // 开关覆盖全链路：关闭后 confirm 也拒
            throw rejection(confirmer, opId, "l1_disabled", "L1 未启用（opspilot.l1.enabled）");
        }
        var row = jdbc.queryForMap(
                "SELECT state, proposed_by, tenant, auth_level, confirm_token_hash, confirm_expires_at, "
                + "command, ref FROM action_ops WHERE op_id = ?", opId);
        // 守卫顺序钉死（2026-10-25 评裁决 3）：身份守卫在状态检查之前，且都不写状态——
        // 否则审计链会出现"合法 confirmed 但无人派发"的诡异记录。
        if (confirmer.sub().equals(row.get("proposed_by"))) {   // 守卫 2：不同 principal
            throw rejection(confirmer, opId, "self_confirm",
                    "确认人不得与提案人相同（防 Agent 自提案自确认）");
        }
        if (!confirmer.tenantId().equals(row.get("tenant"))
                || confirmer.authLevel() < ((Number) row.get("auth_level")).intValue()) {
            throw rejection(confirmer, opId, "confirmer_not_authorized",
                    "确认人租户或密级不匹配");
        }
        if (!"proposed".equals(row.get("state"))) {             // 守卫 4：状态机位置
            throw rejection(confirmer, opId, "state_" + row.get("state"),
                    "op 状态已是 " + row.get("state") + "（不可推进）");
        }
        String storedHash = (String) row.get("confirm_token_hash");
        if (storedHash == null || !MessageDigest.isEqual(
                storedHash.getBytes(), sha256Hex(token).getBytes())) {
            throw rejection(confirmer, opId, "bad_token", "确认 token 无效");
        }
        if (Instant.now().isAfter(((java.sql.Timestamp) row.get("confirm_expires_at")).toInstant())) {
            expire(opId);
            throw rejection(confirmer, opId, "expired", "确认窗口已过（须重新提案）");
        }
        int updated = jdbc.update(
                "UPDATE action_ops SET state='confirmed', confirmed_by=?, confirmed_at=?, "
                + "confirm_token_hash=NULL WHERE op_id=? AND state='proposed' AND confirm_token_hash=?",
                confirmer.sub(), java.sql.Timestamp.from(Instant.now()), opId, storedHash);
        if (updated != 1) {     // 并发双击/竞态：CAS 只允许一次成功
            throw rejection(confirmer, opId, "cas_conflict", "确认已被并发消费");
        }
        auditEvent(confirmer, "action_confirmed", opId);
        l1Metrics.confirmed(confirmer.tenantId());
        dispatch.stampDispatchDeadline(opId);       // 契约 3：confirm 即定派发期限
        dispatch.dispatchConfirmed(opId);           // §2：confirmed→dispatched 系统自动
        return status(confirmer, opId);
    }

    /** 本租户的 op 列表（面板确认入口的数据面）：只看本租户，state 可过滤。 */
    public java.util.List<StatusResult> list(UserContext caller, String stateFilter) {
        freezeSweep();
        String sql = "SELECT op_id, state, command, ref, proposed_by, confirmed_by, tenant, confirm_expires_at "
                + "FROM action_ops WHERE tenant = ?";
        java.util.List<Object> args = new java.util.ArrayList<>(java.util.List.of(caller.tenantId()));
        if (stateFilter != null && !stateFilter.isBlank()) {
            sql += " AND state = ?";
            args.add(stateFilter);
        }
        sql += " ORDER BY proposed_at DESC LIMIT 50";
        return jdbc.query(sql, (rs, i) -> new StatusResult(
                rs.getString("op_id"), rs.getString("state"), rs.getString("command"),
                rs.getString("ref"), rs.getString("proposed_by"), rs.getString("confirmed_by"),
                rs.getString("tenant"),
                rs.getTimestamp("confirm_expires_at") == null ? null
                        : rs.getTimestamp("confirm_expires_at").toInstant().toString()),
                args.toArray());
    }

    public StatusResult status(UserContext caller, String opId) {
        freezeSweep();                              // kill switch 动线：关开关后读路径即冻结
        var row = jdbc.queryForMap(
                "SELECT op_id, state, command, ref, proposed_by, confirmed_by, tenant "
                + "FROM action_ops WHERE op_id = ?", opId);
        if (!caller.tenantId().equals(row.get("tenant"))) {
            throw rejection(caller, opId, "cross_tenant", "不能查他租户的 op");
        }
        return new StatusResult(opId, (String) row.get("state"), (String) row.get("command"),
                (String) row.get("ref"), (String) row.get("proposed_by"),
                (String) row.get("confirmed_by"), (String) row.get("tenant"), null);
    }

    // ── frozen 态（2026-10-25 评裁决·紧急 kill switch 动线） ──────────────
    // 开关关闭时 confirmed 未派发的 op **冻结**（不是 expired）——事故现场人正在救火，
    // 不该被迫重新走一遍提案+确认。frozen 不可派发、不可过期；开关重开后由人显式
    // unfreeze（→ confirmed → 自动派发，期限重置）或 cancel（→ expired）。全部落审计。

    /** 惰性冻结：开关关闭时把 confirmed 未派发的 op 转 frozen（status/list 读路径触发）。 */
    public void freezeSweep() {
        if (dispatch.l1Enabled()) {
            return;
        }
        var rows = jdbc.queryForList("SELECT op_id, tenant FROM action_ops WHERE state='confirmed'");
        for (var row : rows) {
            String opId = (String) row.get("op_id");
            if (jdbc.update("UPDATE action_ops SET state='frozen' "
                    + "WHERE op_id=? AND state='confirmed'", opId) == 1) {
                auditEvent(null, "action_frozen", opId);
                l1Metrics.frozen((String) row.get("tenant"));
            }
        }
    }

    /** 显式解冻（开关重开后由人操作）：frozen → confirmed → 重置期限 → 自动派发。 */
    public StatusResult unfreeze(UserContext actor, String opId) {
        if (!dispatch.l1Enabled()) {
            throw rejection(actor, opId, "l1_disabled", "L1 未启用，不能解冻");
        }
        var row = jdbc.queryForMap(
                "SELECT state, tenant, auth_level FROM action_ops WHERE op_id = ?", opId);
        if (!"frozen".equals(row.get("state"))) {
            throw rejection(actor, opId, "state_" + row.get("state"),
                    "只有 frozen 可解冻（当前 " + row.get("state") + "）");
        }
        if (!actor.tenantId().equals(row.get("tenant"))
                || actor.authLevel() < ((Number) row.get("auth_level")).intValue()) {
            throw rejection(actor, opId, "confirmer_not_authorized", "解冻人租户或密级不匹配");
        }
        int updated = jdbc.update("UPDATE action_ops SET state='confirmed', dispatch_expires_at=? "
                        + "WHERE op_id=? AND state='frozen'",
                java.sql.Timestamp.from(Instant.now().plusSeconds(15 * 60)), opId);
        if (updated != 1) {
            throw rejection(actor, opId, "cas_conflict", "解冻被并发消费");
        }
        auditEvent(actor, "action_unfrozen", opId);
        dispatch.dispatchConfirmed(opId);
        return status(actor, opId);
    }

    /** 取消（frozen → expired）：事故结束/不再需要执行时的正路。 */
    public StatusResult cancel(UserContext actor, String opId) {
        var row = jdbc.queryForMap(
                "SELECT state, tenant, auth_level FROM action_ops WHERE op_id = ?", opId);
        if (!"frozen".equals(row.get("state"))) {
            throw rejection(actor, opId, "state_" + row.get("state"),
                    "只有 frozen 可取消（当前 " + row.get("state") + "）");
        }
        if (!actor.tenantId().equals(row.get("tenant"))) {
            throw rejection(actor, opId, "confirmer_not_authorized", "不能取消他租户的 op");
        }
        if (jdbc.update("UPDATE action_ops SET state='expired' WHERE op_id=? AND state='frozen'",
                opId) == 1) {
            auditEvent(actor, "action_cancelled", opId);
            l1Metrics.expired((String) row.get("tenant"), "confirm");
        }
        return status(actor, opId);
    }

    /** 过期扫描（confirm 前置检查与定时清理共用；红队过期用例的判定点）。 */
    public void expire(String opId) {
        if (jdbc.update("UPDATE action_ops SET state='expired' "
                + "WHERE op_id=? AND state='proposed'", opId) == 1) {
            l1Metrics.expired(tenantOf(opId), "confirm");
        }
    }

    private String tenantOf(String opId) {
        var l = jdbc.queryForList("SELECT tenant FROM action_ops WHERE op_id = ?", String.class, opId);
        return l.isEmpty() ? "" : l.get(0);
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /** 空白归一后的逐字校验（与红队 actions_verbatim 同口径：合并续行不影响判定）。 */
    private static boolean containsVerbatim(String chunkText, String command) {
        return normalize(chunkText).contains(normalize(command));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void auditEvent(UserContext user, String ev, String detail) {
        audit.log(user, ev, "action-api", "manual", detail, null, "none", null, false, 0, 0,
                null, null, null, null);
    }

    private void reject(UserContext user, String ref, String stage, String reason) {
        auditEvent(user, "action_rejected", stage + " " + reason);
        throw new ActionRejectedException(stage, reason);
    }

    private ActionRejectedException rejection(UserContext user, String opId,
                                               String stage, String reason) {
        auditEvent(user, "action_rejected", opId + " " + stage + " " + reason);
        return new ActionRejectedException(stage, reason);
    }

    /** 判定/守卫失败——HTTP 语义由 controller 映射（4xx，不 5xx）。 */
    public static class ActionRejectedException extends RuntimeException {
        public final String stage;
        public ActionRejectedException(String stage, String message) {
            super(message);
            this.stage = stage;
        }
    }
}
