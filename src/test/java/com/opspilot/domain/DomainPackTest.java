package com.opspilot.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 领域包的严格加载与显式选域回归锁（ADR-0031 / 契约 second-domain-accept AC-2 / AC-4）。
 *
 * <p>每条拒绝用例都断言抛 {@link IllegalStateException} 且点名字段，而不是"静默忽略/回落默认"；
 * 拒绝用例一律从合法样本改一处，避免误伤别的判据。生产路径只走 {@link DomainRegistry}
 * 显式解析 + 构造器注入——没有全局单例、没有未初始化回落。
 */
class DomainPackTest {

    private static final String VALID = """
            {
              "schema_version": 1,
              "id": "dba",
              "corpus_path": "offline/domains/dba/corpus/chunks.jsonl",
              "stop_loss_markers": ["止血与恢复"],
              "diagnostic_markers": ["核查步骤"],
              "diagnostic_excludes": ["误判"]
            }
            """;

    @Test
    @DisplayName("SRE 默认包字段等价：五个字段、语料默认路径与段名一字不差")
    void srePackMatchesFrozenFields() {
        DomainPack sre = DomainPack.sre();
        assertEquals("sre", sre.id());
        assertEquals("offline/corpus/chunks.jsonl", sre.corpusPath());
        assertEquals(List.of("止损操作"), sre.stopLossMarkers());
        assertEquals(List.of("排查"), sre.diagnosticMarkers());
        assertEquals(List.of("陷阱", "误判"), sre.diagnosticExcludes());
        // 记录成分是五个字段，没有 prompt / 文案槽位：靠 record 的 component 数钉死
        assertEquals(5, DomainPack.class.getRecordComponents().length);
    }

    @Test
    @DisplayName("DBA 包字段：独立语料根 + 独立段名，与 SRE 零撞名")
    void dbaPackHasDifferentCorpusAndMarkers() {
        DomainPack dba = DomainPack.load("dba");
        assertEquals("dba", dba.id());
        assertEquals("offline/domains/dba/corpus/chunks.jsonl", dba.corpusPath());
        assertEquals(List.of("止血与恢复"), dba.stopLossMarkers());
        assertEquals(List.of("核查步骤"), dba.diagnosticMarkers());
        assertEquals(List.of("误判", "陷阱"), dba.diagnosticExcludes());
        assertTrue(dba.stopLossMarkers().stream().noneMatch(
                m -> DomainPack.sre().stopLossMarkers().contains(m)));
    }

    @Test
    @DisplayName("选域：未设/空串→sre；dba→dba；未知/斜杠/../空白/大写→启动失败并点名 id")
    void selectionIsExplicitAndFailClosed() {
        assertEquals("sre", DomainRegistry.resolve(null));
        assertEquals("sre", DomainRegistry.resolve(""));
        assertEquals("dba", DomainRegistry.resolve("dba"));
        for (String bad : List.of("unknown", "dba/x", "../dba", " dba", "dba ", "DBA", "SRE")) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> DomainRegistry.resolve(bad), "非法 id 必须拒绝: " + bad);
            assertTrue(ex.getMessage().contains(bad), "异常要点名非法 id: " + bad);
            assertTrue(ex.getMessage().contains("opspilot.domain"), "异常要点名选择口: " + bad);
        }
    }

    @Test
    @DisplayName("缺 manifest / 未知 schema_version / 未知顶层与嵌套键 → 拒绝")
    void rejectsMissingManifestAndUnknownKeys() {
        assertThrows(IllegalStateException.class, () -> DomainPack.load("missing-domain"));
        assertRejected(VALID.replace("\"schema_version\": 1", "\"schema_version\": 2"),
                "schema_version");
        assertRejected(VALID.replace("\"schema_version\": 1", "\"schema_version\": 1.0"),
                "schema_version");
        assertRejected(VALID.replace("{\n", "{\n  \"extra\": true,\n"), "顶层键");
        assertRejected(VALID.replace("\"id\": \"dba\"", "\"id\": \"dba\",\n  \"prompt\": \"x\""),
                "顶层键");
        assertRejected(VALID.replace("\"diagnostic_excludes\": [\"误判\"]",
                "\"diagnostic_excludes\": [\"误判\"],\n  \"on_call\": \"x\""), "顶层键");
        assertRejected(VALID.replace("\"id\": \"dba\"", "\"id\": \"\""), "id");
    }

    @Test
    @DisplayName("空段名列表/空项/重复项/相交/正则元字符/非法语料路径 → 拒绝")
    void rejectsBadManifestData() {
        assertRejected(VALID.replace("\"stop_loss_markers\": [\"止血与恢复\"]",
                "\"stop_loss_markers\": []"), "非空数组");
        assertRejected(VALID.replace("\"diagnostic_markers\": [\"核查步骤\"]",
                "\"diagnostic_markers\": [\" \"]"), "非空字符串");
        assertRejected(VALID.replace("\"diagnostic_excludes\": [\"误判\"]",
                "\"diagnostic_excludes\": [\"误判\", \"误判\"]"), "重复");
        // 相交：一段同时可抽取又已止血，段名闸会被自己的数据绕开
        assertRejected(VALID.replace("\"diagnostic_markers\": [\"核查步骤\"]",
                "\"diagnostic_markers\": [\"止血与恢复\"]"), "相交");
        assertRejected(VALID.replace("\"diagnostic_markers\": [\"核查步骤\"]",
                "\"diagnostic_markers\": [\"止血\"]"), "相交");
        for (String bad : List.of(".*", "(", ")", "|", "a|b", "[abc]", "a/b", "a b")) {
            assertRejected(VALID.replace("\"核查步骤\"", "\"" + bad + "\""), "正则元字符");
        }
        for (String bad : List.of("/abs/chunks.jsonl", "../chunks.jsonl",
                "domains/dba/corpus/chunks.jsonl", "offline/../etc/passwd")) {
            assertRejected(VALID.replace("offline/domains/dba/corpus/chunks.jsonl", bad),
                    "corpus_path");
        }
    }

    @Test
    @DisplayName("manifest id 与选择口不一致 → 拒绝（数据不能冒充另一个领域）")
    void rejectsIdMismatch() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DomainPack.parse(VALID, "sre"));
        assertTrue(ex.getMessage().contains("id"), ex.getMessage());
    }

    @Test
    @DisplayName("解析失败不静默回落：坏 JSON / 非对象根节点拒绝")
    void rejectsMalformedPayloads() {
        assertRejected("not json", "合法 JSON");
        assertRejected("[]", "JSON 对象");
        assertRejected("null", "JSON 对象");
    }

    private static void assertRejected(String json, String messagePart) {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DomainPack.parse(json, "dba"));
        assertTrue(ex.getMessage().contains(messagePart),
                "异常信息应含「" + messagePart + "」，实际：" + ex.getMessage());
    }
}
