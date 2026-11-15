package com.opspilot.action;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 白名单数据文件的 schema 闭集与加载失败路径（ADR-0029 首个切面，契约 AC-3 a–f）。
 * 每条拒绝用例都断言"抛异常"，而不是"静默忽略/回落默认值"。
 */
class CommandPolicyDataTest {

    /** 合法最小样本：每个拒绝用例只改其中一处，避免误伤其它判据。 */
    private static final String VALID = """
            {
              "schema_version": 1,
              "families": {
                "kubectl-rollout": {
                  "prefixes": ["kubectl rollout restart"],
                  "flags": ["-n"]
                },
                "systemctl-unit": {
                  "prefixes": ["systemctl restart"],
                  "units": ["nginx"]
                }
              }
            }
            """;

    @Test
    void classpathDataMatchesFrozenSchemaAndWhitelist() {
        CommandPolicyData data = CommandPolicyData.load();
        Map<String, CommandPolicyData.Family> byId = byId(data);
        assertEquals(Set.of("kubectl-rollout", "systemctl-unit"), byId.keySet(), "家族闭集");
        assertEquals(List.of("kubectl rollout restart", "kubectl rollout undo", "kubectl rollout status"),
                byId.get("kubectl-rollout").prefixes(), "前缀列表");
        assertEquals(Set.of("-n", "--namespace"), byId.get("kubectl-rollout").flags(), "旗标集合");
        assertEquals(Set.of(), byId.get("kubectl-rollout").units(), "旗标型家族不收宾语");
        assertEquals(List.of("systemctl restart"), byId.get("systemctl-unit").prefixes(), "前缀列表");
        assertEquals(Set.of("nginx", "redis", "mysql", "order-api", "inventory-api"),
                byId.get("systemctl-unit").units(), "宾语集合");
        assertEquals(Set.of(), byId.get("systemctl-unit").flags(), "单宾语家族不收旗标");
    }

    @Test
    void rejectsUnknownSchemaVersionAndUnknownKeys() {
        assertRejected(VALID.replace("\"schema_version\": 1", "\"schema_version\": 2"),
                "schema_version");
        assertRejected(VALID.replace("\"schema_version\": 1", "\"schema_version\": 1.0"),
                "schema_version");
        assertRejected(VALID.replace("\"families\": {", "\"extra\": true,\n  \"families\": {"),
                "根节点");
        assertRejected(VALID.replace("\"prefixes\": [\"kubectl rollout restart\"],\n"
                        + "      \"flags\": [\"-n\"]",
                "\"prefixes\": [\"kubectl rollout restart\"],\n"
                        + "      \"flags\": [\"-n\"],\n"
                        + "      \"units\": [\"nginx\"]"),
                "家族 kubectl-rollout");
        assertRejected(VALID.replace("\"prefixes\": [\"kubectl rollout restart\"],\n"
                        + "      \"flags\": [\"-n\"]",
                "\"flags\": [\"-n\"]"),
                "家族 kubectl-rollout");          // 缺 prefixes
    }

    @Test
    void rejectsUnknownFamilyId() {
        assertRejected(VALID.replace("\"systemctl-unit\"", "\"journalctl-unit\""), "未知命令家族");
        assertRejected(VALID.replace("\"kubectl-rollout\"", "\"kubectl-exec\""), "未知命令家族");
        assertRejected(VALID.replace("\"families\": {", "\"families\": {\n    \"kubectl-extra\": "
                        + "{\"prefixes\": [\"kubectl extra\"], \"flags\": [\"-n\"]},"),
                "未知命令家族");
    }

    @Test
    void rejectsDuplicateEntries() {
        assertRejected(VALID.replace("\"prefixes\": [\"kubectl rollout restart\"]",
                "\"prefixes\": [\"kubectl rollout restart\", \"kubectl rollout restart\"]"),
                "重复前缀");
        assertRejected(VALID.replace("\"flags\": [\"-n\"]", "\"flags\": [\"-n\", \"-n\"]"),
                "重复旗标");
        assertRejected(VALID.replace("\"units\": [\"nginx\"]", "\"units\": [\"nginx\", \"nginx\"]"),
                "重复宾语");
        // 前缀跨家族重复同样拒绝：前缀 → 家族必须是单值映射。
        assertRejected(VALID.replace("\"systemctl restart\"", "\"kubectl rollout restart\""),
                "重复");
    }

    @Test
    void rejectsNonLiteralPrefixes() {
        for (String bad : List.of(
                "kubectl rollout *",          // 通配
                "kubectl rollout .*",         // 正则
                "kubectl rollout restart.*",
                "Kubectl rollout restart",    // 大写
                "kubectl  rollout restart",   // 双空格（非单空格分隔）
                " kubectl rollout restart",   // 首部空白
                "kubectl rollout restart ",   // 尾部空白
                "kubectl rollout restart; rm -rf /tmp",   // 链式元字符
                "kubectl rollout restart | tee /tmp/x",
                "kubectl rollout restart $(id)",
                "kubectl rollout $(id)",
                "kubectl rollout restart > /tmp/x",
                "kubectl rollout restart && id",
                "kubectl rollout restart #x",
                "kubectl rollout restart &",
                "")) {
            assertRejected(VALID.replace("\"kubectl rollout restart\"", "\"" + bad + "\""),
                    "家族 kubectl-rollout 的 prefixes");
        }
    }

    @Test
    void rejectsOrderDependentPrefixes() {
        assertRejected(VALID.replace(
                        "\"prefixes\": [\"kubectl rollout restart\"]",
                        "\"prefixes\": [\"kubectl rollout\", \"kubectl rollout restart\"]"),
                "真前缀");
        assertRejected(VALID.replace(
                        "\"prefixes\": [\"kubectl rollout restart\"]",
                        "\"prefixes\": [\"kubectl rollout restart\", \"kubectl rollout restart undo\"]"),
                "真前缀");
    }

    @Test
    void rejectsIllegalFlagsAndUnits() {
        for (String badFlag : List.of("--kubeconfig=/tmp/evil", "-N", "namespace", "-", "--",
                "--name space", "-n;id", "*")) {
            assertRejected(VALID.replace("\"flags\": [\"-n\"]", "\"flags\": [\"" + badFlag + "\"]"),
                    "旗标");
        }
        for (String badUnit : List.of("Nginx", "-nginx", "nginx unit", "nginx=", "nginx*",
                "nginx;id", "")) {
            assertRejected(VALID.replace("\"units\": [\"nginx\"]", "\"units\": [\"" + badUnit + "\"]"),
                    "宾语");
        }
        assertRejected(VALID.replace("\"units\": [\"nginx\"]", "\"units\": [\"nginx\", 42]"),
                "不是字符串");
    }

    @Test
    void rejectsEmptySetsAndEmptyFamilies() {
        assertRejected(VALID.replace("\"prefixes\": [\"systemctl restart\"]", "\"prefixes\": []"),
                "空前缀集");
        assertRejected(VALID.replace("\"flags\": [\"-n\"]", "\"flags\": []"), "空旗标集");
        assertRejected(VALID.replace("\"units\": [\"nginx\"]", "\"units\": []"), "空宾语集");
        assertRejected("""
                {"schema_version": 1, "families": {}}
                """, "families 不得为空");
    }

    @Test
    void failsWhenClasspathResourceIsMissingOrUnreadable() {
        ClassLoader missing = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;                    // 模拟 classpath 缺文件：不得回落默认白名单
            }
        };
        IllegalStateException missingEx = assertThrows(IllegalStateException.class,
                () -> CommandPolicyData.load(missing));
        assertTrue(missingEx.getMessage().contains("policy/command-policy.json"),
                missingEx.getMessage());

        ClassLoader unreadable = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("模拟读取失败");
                    }
                };
            }
        };
        assertThrows(IllegalStateException.class, () -> CommandPolicyData.load(unreadable));
    }

    @Test
    void rejectsMalformedOrNonObjectPayloads() {
        assertRejected("not json", "合法 JSON");
        assertRejected("[]", "根节点");
        assertRejected("null", "根节点");
        assertRejected("""
                {"schema_version": 1, "families": []}
                """, "families");
    }

    private static Map<String, CommandPolicyData.Family> byId(CommandPolicyData data) {
        Map<String, CommandPolicyData.Family> byId = new HashMap<>();
        for (CommandPolicyData.Family family : data.families()) {
            byId.put(family.id(), family);
        }
        return byId;
    }

    private static void assertRejected(String json, String messagePart) {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> CommandPolicyData.parse(json));
        assertTrue(ex.getMessage().contains(messagePart),
                "异常信息应含「" + messagePart + "」，实际：" + ex.getMessage());
    }
}
