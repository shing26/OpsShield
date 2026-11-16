package com.opspilot.action;

import org.junit.jupiter.api.Test;
import com.opspilot.action.ControlledCommandPolicy.Verdict;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** L1 判定：三通道顺序 + 注入不豁免 + 宾语/旗标白名单（ADR-0028 规格 §3）。 */
class ControlledCommandPolicyTest {

    @Test
    void l0ReadStillPassesUnchanged() {
        assertEquals(Verdict.L0_READ, ControlledCommandPolicy.classify(
                "kubectl get pods -n prod", false));
        assertEquals(Verdict.L0_READ, ControlledCommandPolicy.classify(
                "cat /var/log/syslog | grep error", false));
    }

    @Test
    void l1RequiresBothChunkDeclarationAndWhitelist() {
        String cmd = "kubectl rollout restart deployment/order-api -n prod";
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(cmd, false));   // 缺文档声明
        assertEquals(Verdict.L1_WRITE_LOW, ControlledCommandPolicy.classify(cmd, true));
    }

    @Test
    void l1InjectionNeverExempted() {
        // 红队 l1-contract-hidden-cmd 的判据：第二命令藏链式
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(
                "kubectl rollout restart deployment/x -n prod; rm -rf /tmp", true));
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(
                "kubectl rollout restart deployment/x && cat /etc/passwd", true));
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(
                "kubectl rollout restart $(echo deployment/x)", true));
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(
                "kubectl rollout restart deployment/x > /tmp/pwn", true));
    }

    @Test
    void tailWhitelistBlocksUnknownFlagsAndExoticNames() {
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(
                "kubectl rollout restart deployment/x --kubeconfig=/tmp/evil", true));
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(
                "kubectl rollout restart deployment/x -n `id`", true));
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(
                "systemctl restart hacked-unit", true));
        assertEquals(Verdict.L1_WRITE_LOW, ControlledCommandPolicy.classify(
                "systemctl restart nginx", true));
    }

    @Test
    void undeclaredWriteVerbsStillRejected() {
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify("rm -rf /", true));
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(
                "kubectl delete namespace prod", true));
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify("", true));
        assertEquals(Verdict.REJECT, ControlledCommandPolicy.classify(null, true));
    }

    // ── AC-4(a)：加载后的闭集与契约冻结集合逐项相等（数据文件是唯一事实源）──────────

    @Test
    void loadedWhitelistEqualsFrozenSets() {
        Map<String, CommandPolicyData.Family> byId = new HashMap<>();
        for (CommandPolicyData.Family family : CommandPolicyData.load().families()) {
            byId.put(family.id(), family);
        }
        assertEquals(Set.of("kubectl-rollout", "systemctl-unit"), byId.keySet());
        assertEquals(List.of("kubectl rollout restart", "kubectl rollout undo", "kubectl rollout status"),
                byId.get("kubectl-rollout").prefixes());
        assertEquals(Set.of("-n", "--namespace"), byId.get("kubectl-rollout").flags());
        assertEquals(Set.of("nginx", "redis", "mysql", "order-api", "inventory-api"),
                byId.get("systemctl-unit").units());
        assertEquals(List.of("systemctl restart"), byId.get("systemctl-unit").prefixes());
    }

    // ── AC-4(b)：14 个输入与 main c0b971a 逐项等价 ────────────────────────────

    @Test
    void verdictsMatchFrozenFourteenInputs() {
        assertEquals(Verdict.L0_READ, verdict("kubectl get pods -n prod", false));
        assertEquals(Verdict.L0_READ, verdict("cat /var/log/syslog | grep error", false));
        assertEquals(Verdict.REJECT,
                verdict("kubectl rollout restart deployment/order-api -n prod", false));
        assertEquals(Verdict.L1_WRITE_LOW,
                verdict("kubectl rollout restart deployment/order-api -n prod", true));
        assertEquals(Verdict.L1_WRITE_LOW,
                verdict("kubectl rollout undo deployment/order-api -n prod", true));
        assertEquals(Verdict.L1_WRITE_LOW,
                verdict("kubectl rollout status deployment/order-api -n prod", true));
        assertEquals(Verdict.L1_WRITE_LOW, verdict("systemctl restart nginx", true));
        assertEquals(Verdict.REJECT, verdict("systemctl restart hacked-unit", true));
        assertEquals(Verdict.REJECT,
                verdict("kubectl rollout restart deployment/x -n prod; rm -rf /tmp", true));
        assertEquals(Verdict.REJECT,
                verdict("kubectl rollout restart deployment/x && cat /etc/passwd", true));
        assertEquals(Verdict.REJECT,
                verdict("kubectl rollout restart $(echo deployment/x)", true));
        assertEquals(Verdict.REJECT,
                verdict("kubectl rollout restart deployment/x > /tmp/pwn", true));
        assertEquals(Verdict.REJECT,
                verdict("kubectl rollout restart deployment/x --kubeconfig=/tmp/evil", true));
        assertEquals(Verdict.REJECT, verdict("systemctl restart nginx extra", true));
    }

    // ── AC-4(c)：目标抽取与冻结行为逐项相等 ────────────────────────────────

    @Test
    void targetExtractionUnchanged() {
        assertEquals("deployment/order-api", ControlledCommandPolicy.targetOf(
                "kubectl rollout restart deployment/order-api -n prod"));
        assertEquals("nginx", ControlledCommandPolicy.targetOf("systemctl restart nginx"));
        assertNull(ControlledCommandPolicy.targetOf("systemctl restart nginx extra"));
        assertNull(ControlledCommandPolicy.targetOf("systemctl restart"));
        assertNull(ControlledCommandPolicy.targetOf("kubectl rollout restart"));
        // 前缀识别从数据派生：不在数据前缀集里的命令不再抽目标（原实现按 kubectl 前缀前缀式兜底）
        assertNull(ControlledCommandPolicy.targetOf("kubectl get pods -n prod"));
        assertNull(ControlledCommandPolicy.targetOf(null));
    }

    private static Verdict verdict(String command, boolean declaresL1) {
        return ControlledCommandPolicy.classify(command, declaresL1);
    }
}
