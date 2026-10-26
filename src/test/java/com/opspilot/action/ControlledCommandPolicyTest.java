package com.opspilot.action;

import org.junit.jupiter.api.Test;
import com.opspilot.action.ControlledCommandPolicy.Verdict;

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
}
