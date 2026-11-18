package com.opspilot.action;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L0 只读命令策略数据文件的 schema 闭集与加载失败路径（ADR-0029 第二个切面，契约 AC-3）。
 * 每条拒绝用例都断言"抛异常"，而不是"静默忽略/回落默认值"。
 */
class ReadOnlyCommandPolicyDataTest {

    /** 合法最小样本：每个拒绝用例只改其中一处，避免误伤其它判据。 */
    private static final String VALID = """
            {
              "schema_version": 1,
              "tools": ["ps", "redis-cli", "kubectl"],
              "verbs": {
                "kubectl": ["get"], "docker": ["ps"], "git": ["status"],
                "systemctl": ["status"], "service": ["status"],
                "timedatectl": ["status"], "chronyc": ["tracking"],
                "openssl": ["x509"], "redis-cli": ["slowlog"]
              },
              "subverbs": {"redis-cli": {"slowlog": ["get"]}}
            }
            """;

    /** 已知宇宙必须是代码字面量（62 / 9 项），不得由数据派生——否则"未知工具"退化为同义反复。 */
    @Test
    void knownUniverseEqualsFrozenSets() {
        assertEquals(62, knownTools().size(), "KNOWN_TOOLS 项数");
        assertEquals(Set.of("kubectl", "docker", "git", "systemctl", "service",
                        "timedatectl", "chronyc", "openssl", "redis-cli"),
                verbTools(), "VERB_TOOLS 闭集");
        for (String forbidden : Set.of("sh", "bash", "zsh", "dash", "ash", "powershell",
                "pwsh", "ssh", "find", "sed", "env", "wget", "tcpdump")) {
            assertTrue(!knownTools().contains(forbidden), "机制入口不得进已知宇宙：" + forbidden);
        }
        assertEquals(Set.of(
                "ps", "top", "uptime", "free", "vmstat", "iostat", "mpstat", "pidstat", "sar",
                "ls", "cat", "file", "stat", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "jq",
                "grep", "egrep", "fgrep", "rg",
                "echo", "printf", "date", "printenv", "id", "whoami", "hostname", "uname",
                "dmesg", "lsb_release",
                "ping", "traceroute", "tracepath", "ss", "netstat", "dig", "nslookup", "host", "curl",
                "timedatectl", "chronyc", "ntpq", "ntpstat",
                "jps", "jstack", "jstat", "jinfo", "jmap",
                "keytool", "openssl", "journalctl",
                "kubectl", "docker", "git", "systemctl", "service", "redis-cli"),
                knownTools(), "KNOWN_TOOLS 冻结内容（56 纯读 ∪ 6 verbs 独有）");
    }

    @Test
    void unknownToolFails() {
        assertRejected(VALID.replace("\"ps\", \"redis-cli\", \"kubectl\"",
                "\"ps\", \"rm\", \"kubectl\""), "未知工具");
        assertRejected(VALID.replace("\"ps\", \"redis-cli\", \"kubectl\"",
                "\"ps\", \"sh\", \"kubectl\""), "未知工具");   // 外壳是结构性入口，不得借数据扩权
        assertRejected(VALID.replace("\"subverbs\": {\"redis-cli\"",
                "\"subverbs\": {\"env\""), "未知工具");
    }

    @Test
    void unknownVerbFamilyFails() {
        assertRejected(VALID.replace("\"kubectl\": [\"get\"]", "\"rm\": [\"get\"]"),
                "verbs 的键必须恰为");
        assertRejected(VALID.replace("\"chronyc\": [\"tracking\"],", ""),
                "verbs 的键必须恰为");
        assertRejected(VALID.replace("\"subverbs\": {\"redis-cli\"",
                "\"subverbs\": {\"ps\""), "不是动词家族");
        assertRejected(VALID.replace("\"slowlog\": [\"get\"]", "\"flushall\": [\"get\"]"),
                "该工具动词集以外的键");
    }

    @Test
    void duplicateJsonKeyFails() {
        // 重复 JSON 对象键必须由加载器自身的严格解析检测，不能依赖后值覆盖前值。
        assertRejected(VALID.replace("\"schema_version\": 1,",
                "\"schema_version\": 1, \"schema_version\": 1,"), "uplicate");
        assertRejected(VALID.replace("\"subverbs\": {\"redis-cli\": {\"slowlog\": [\"get\"]}}",
                "\"subverbs\": {\"redis-cli\": {\"slowlog\": [\"get\"], \"slowlog\": [\"get\"]}}"),
                "uplicate");
    }

    @Test
    void emptySetFails() {
        assertRejected(VALID.replace("\"tools\": [\"ps\", \"redis-cli\", \"kubectl\"]",
                "\"tools\": []"), "空工具集");
        assertRejected(VALID.replace("\"kubectl\": [\"get\"]", "\"kubectl\": []"), "空动词集");
        assertRejected(VALID.replace("\"slowlog\": [\"get\"]", "\"slowlog\": []"), "空子动词集");
        assertRejected(VALID.replace("\"subverbs\": {\"redis-cli\": {\"slowlog\": [\"get\"]}}",
                "\"subverbs\": {}"), "subverbs 不得为空");
        assertRejected(VALID.replace("\"subverbs\": {\"redis-cli\": {\"slowlog\": [\"get\"]}}",
                "\"subverbs\": {\"redis-cli\": {}}"), "不得为空");
    }

    @Test
    void missingResourceFails() {
        ClassLoader missing = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        };
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReadOnlyCommandPolicyData.load(missing));
        assertTrue(ex.getMessage().contains(ReadOnlyCommandPolicyData.RESOURCE_PATH),
                ex.getMessage());
    }

    @Test
    void unreadableResourceFails() {
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
        assertThrows(IllegalStateException.class, () -> ReadOnlyCommandPolicyData.load(unreadable));
    }

    @Test
    void invalidSchemaVersionFails() {
        assertRejected(VALID.replace("\"schema_version\": 1", "\"schema_version\": 2"),
                "schema_version");
        assertRejected(VALID.replace("\"schema_version\": 1", "\"schema_version\": 1.0"),
                "schema_version");
        assertRejected(VALID.replace("\"schema_version\": 1", "\"schema_version\": \"1\""),
                "schema_version");
        assertRejected(VALID.replace("\"schema_version\": 1", "\"schema_version\": null"),
                "schema_version");
    }

    @Test
    void rejectsUnknownKeysAndTypeErrors() {
        assertRejected(VALID.replace("\"schema_version\": 1,",
                "\"extra\": true,\n  \"schema_version\": 1,"), "根节点");
        assertRejected(VALID.replace("\"schema_version\": 1,\n  ", ""), "根节点");
        assertRejected("not json", "合法 JSON");
        assertRejected("[]", "根节点");
        assertRejected(VALID.replace("\"tools\": [\"ps\", \"redis-cli\", \"kubectl\"]",
                "\"tools\": \"ps\""), "tools 必须是非空数组");
        assertRejected(VALID.replace("\"tools\": [\"ps\", \"redis-cli\", \"kubectl\"]",
                "\"tools\": [\"ps\", 42, \"kubectl\"]"), "不是字符串");
        assertRejected(VALID.replace("\"kubectl\": [\"get\"]", "\"kubectl\": {\"get\": true}"),
                "非空数组");
        assertRejected(VALID.replaceFirst("(?s)\"verbs\": \\{.*?\\n  \\},", "\"verbs\": [],"),
                "verbs 必须是 JSON 对象");
        assertRejected(VALID.replace("\n  \"subverbs\": {\"redis-cli\": {\"slowlog\": [\"get\"]}}",
                "\n  \"subverbs\": []"), "subverbs 必须是 JSON 对象");
        assertRejected(VALID.replace("\"slowlog\": [\"get\"]", "\"slowlog\": [[\"get\"]]"),
                "不是字符串");
    }

    @Test
    void rejectsDuplicateArrayItems() {
        assertRejected(VALID.replace("\"ps\", \"redis-cli\", \"kubectl\"",
                "\"ps\", \"ps\", \"kubectl\""), "重复工具");
        assertRejected(VALID.replace("\"kubectl\": [\"get\"]", "\"kubectl\": [\"get\", \"get\"]"),
                "重复动词");
        assertRejected(VALID.replace("\"slowlog\": [\"get\"]", "\"slowlog\": [\"get\", \"get\"]"),
                "重复子动词");
    }

    @Test
    void rejectsWildcardsMetacharactersWhitespaceAndCase() {
        // 工具/子动词闭集只允许小写字面量；这里用 JSON 转义形态覆盖空白与引号。
        for (String bad : Set.of("*", ".*", "PS", " ps", "ps ", "ps;id", "ps|id", "ps&id",
                "ps$", "ps`id`", "ps\\n", "ps\\t", "ps?id", "ps[id]", "ps{x}", "ps(1)",
                "ps>out", "ps<in", "ps'x'", "ps\\\"x\\\"", "ps\\\\x", "--flag=value", "")) {
            assertRejected(VALID.replace("\"ps\", \"redis-cli\", \"kubectl\"",
                    "\"" + bad + "\", \"redis-cli\", \"kubectl\""), "非法工具");
            assertRejected(VALID.replace("\"slowlog\": [\"get\"]",
                    "\"slowlog\": [\"" + bad + "\"]"), "非法子动词");
        }
        // 动词闭集允许大写（合成夹具验证逐字保留），但元字符/空白仍拒绝；大小写归一被禁止。
        for (String bad : Set.of("*", ".*", " get", "get ", "get;id", "get|id", "VM version",
                "get$", "get`id`", "get\\n", "get\\t", "get?id", "get[id]", "get{x}",
                "get(1)", "get>out", "--flag=value", "")) {
            assertRejected(VALID.replace("\"kubectl\": [\"get\"]",
                    "\"kubectl\": [\"" + bad + "\"]"), "非法动词");
        }
        ReadOnlyCommandPolicyData parsed = ReadOnlyCommandPolicyData.parse(
                VALID.replace("\"kubectl\": [\"get\"]", "\"kubectl\": [\"Get\"]"));
        assertTrue(parsed.verbs().get("kubectl").contains("Get"), "合成夹具的大写动词原样保留（不归一）");
    }

    @Test
    void acceptsValidSampleWithUppercaseVerbs() {
        ReadOnlyCommandPolicyData parsed = ReadOnlyCommandPolicyData.parse(VALID);
        assertEquals(Set.of("ps", "redis-cli", "kubectl"), parsed.tools());
        assertEquals(Map.of("slowlog", Set.of("get")), parsed.subverbs().get("redis-cli"));
    }

    /** 反射读取代码字面量，证明它们不来自数据（AC-2 机械判据的行为侧印证）。 */
    @SuppressWarnings("unchecked")
    private static Set<String> knownTools() {
        return (Set<String>) field("KNOWN_TOOLS");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> verbTools() {
        return (Set<String>) field("VERB_TOOLS");
    }

    private static Object field(String name) {
        try {
            Field f = ReadOnlyCommandPolicyData.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertRejected(String json, String messagePart) {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReadOnlyCommandPolicyData.parse(json),
                "应拒绝：" + messagePart);
        assertTrue(ex.getMessage().contains(messagePart),
                "异常信息应含「" + messagePart + "」，实际：" + ex.getMessage());
    }
}
