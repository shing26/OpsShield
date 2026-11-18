package com.opspilot.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.opspilot.domain.DomainPack;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.retrieval.ScoredChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * command-yield-accept AC-4 语料级 golden：rb-115 / rb-103 新增的只读排查段必须**逐条过 L0**，
 * 且抽出的行动契约命令必须是引用 chunk 原文的逐字子串。
 *
 * <p>这不是"策略放宽"测试：命令若判拒，修的是语料命令，不是白名单。原「第三步」「处置」
 * 「止损操作」段的写命令保持原样，本测试用逐字断言锁住它们没有漂移进契约。
 */
class ActionYieldGoldenTest {

    private static final ObjectMapperHolder MAPPER = new ObjectMapperHolder();
    private static List<ScoredChunk> chunks;

    /** 避免与测试类同名的静态初始化噪音：仅包一层 Jackson mapper（tools.jackson）。 */
    private static final class ObjectMapperHolder {
        final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
    }

    @BeforeAll
    static void loadCorpus() throws Exception {
        Path file = Path.of("offline/corpus/chunks.jsonl");
        assertTrue(Files.exists(file), "缺 offline/corpus/chunks.jsonl，先跑 chunkers/build_chunks.py");
        chunks = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            var j = MAPPER.mapper.readTree(line);
            chunks.add(new ScoredChunk(j.get("chunk_id").asText(), j.get("doc_id").asText(),
                    j.get("type").asText(), j.get("text").asText(),
                    j.get("breadcrumb").asText(),
                    j.path("metadata").path("service").asText(""),
                    List.of(), 1, new ScoredChunk.Scores(0, 0, 0, 0)));
        }
    }

    private static ScoredChunk leaf(String docId, String marker) {
        return chunks.stream()
                .filter(c -> c.docId().equals(docId))
                .filter(c -> c.breadcrumb().contains(marker))
                .filter(c -> c.text().contains("```"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到 " + docId + " 的 " + marker + " 围栏 chunk"));
    }

    private static List<String> fencedCommands(ScoredChunk c) {
        List<String> out = new ArrayList<>();
        boolean inside = false;
        for (String raw : c.text().lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                inside = !inside;
                continue;
            }
            if (inside && !line.isEmpty() && !line.startsWith("#")) {
                out.add(line);
            }
        }
        return out;
    }

    @Test
    void rb115PreflightFencedCommandsAreReadOnlyAndVerbatim() {
        ScoredChunk preflight = leaf("rb-115", "只读预检");
        List<String> commands = fencedCommands(preflight);
        assertEquals(4, commands.size(), "预检围栏应含 4 条只读命令: " + commands);
        for (String command : commands) {
            assertTrue(ReadOnlyCommandPolicy.isReadOnly(command),
                    "预检命令未过 L0（应改语料命令，不得放宽策略）: " + command);
            assertTrue(preflight.text().contains(command), "命令必须是 chunk 原文逐字子串: " + command);
        }
        assertTrue(ReadOnlyActionExtractor.isDiagnosticActionLeaf(preflight, DomainPack.sre()),
                "预检段必须是可被选槽的诊断叶");
    }

    @Test
    void rb103InvestigationFencedCommandsAreReadOnlyAndVerbatim() {
        ScoredChunk investigation = leaf("rb-103", "排查步骤");
        List<String> commands = fencedCommands(investigation);
        assertEquals(2, commands.size(), "排查步骤围栏应含 2 条只读命令: " + commands);
        for (String command : commands) {
            assertTrue(ReadOnlyCommandPolicy.isReadOnly(command),
                    "排查命令未过 L0（应改语料命令，不得放宽策略）: " + command);
            assertTrue(investigation.text().contains(command), "命令必须是 chunk 原文逐字子串: " + command);
        }
        assertTrue(ReadOnlyActionExtractor.isDiagnosticActionLeaf(investigation, DomainPack.sre()),
                "排查步骤段必须是可被选槽的诊断叶");
    }

    @Test
    void extractedActionsAreVerbatimAndStayInTop1Document() {
        OpsMetrics metrics = new OpsMetrics(new SimpleMeterRegistry());
        ReadOnlyActionExtractor extractor = new ReadOnlyActionExtractor(metrics);
        for (String[] spec : List.of(
                new String[]{"rb-115", "只读预检"}, new String[]{"rb-103", "排查步骤"})) {
            ScoredChunk source = leaf(spec[0], spec[1]);
            List<Action> actions = extractor.extract(List.of(source));
            assertFalse(actions.isEmpty(), spec[0] + " 诊断叶必须能抽出只读行动契约");
            for (Action a : actions) {
                assertNotNull(a.command());
                assertFalse(a.command().isBlank());
                assertEquals(source.chunkId(), a.ref(), "action ref 必须属于 Top-1 文档同一 chunk");
                assertTrue(source.text().contains(a.command()),
                        "action.command 必须是 ref chunk 原文的逐字子串: " + a.command());
                assertTrue(ReadOnlyCommandPolicy.isReadOnly(a.command()),
                        "抽出的 action 必须过 L0: " + a.command());
            }
            for (int i = 0; i < actions.size(); i++) {
                assertEquals(i + 1, actions.get(i).n(), "编号连续 1..N");
            }
        }
    }

    @Test
    void writeSectionsAreNotDiagnosticLeaves() {
        for (ScoredChunk c : chunks) {
            if (!c.docId().equals("rb-115") && !c.docId().equals("rb-103")) {
                continue;
            }
            String[] parts = c.breadcrumb().split(" > ", -1);
            if (parts.length < 2) {
                continue;
            }
            String section = parts[1];
            if (section.contains("止损") || section.contains("处置")) {
                assertFalse(ReadOnlyActionExtractor.isDiagnosticActionLeaf(c, DomainPack.sre()),
                        "写操作段不得被选为诊断叶: " + c.chunkId());
            }
        }
    }

    @Test
    void originalThirdStepAndStopLossTextIsPreserved() {
        ScoredChunk third = chunks.stream()
                .filter(c -> c.docId().equals("rb-115"))
                .filter(c -> c.breadcrumb().contains("第三步"))
                .findFirst().orElseThrow();
        assertTrue(third.text().contains("java -cp h2-<版本>.jar org.h2.tools.Recover -dir data -db users"),
                "第三步的 java Recover 命令须逐字保留");
        ScoredChunk stopLoss = chunks.stream()
                .filter(c -> c.docId().equals("rb-115"))
                .filter(c -> c.breadcrumb().contains("止损操作"))
                .findFirst().orElseThrow();
        assertTrue(stopLoss.text().contains("bash scripts/seed_demo_users.sh"),
                "止损操作的重播种命令须逐字保留");
        ScoredChunk disposal = chunks.stream()
                .filter(c -> c.docId().equals("rb-103"))
                .filter(c -> c.breadcrumb().endsWith("处置"))
                .findFirst().orElseThrow();
        assertTrue(disposal.text().contains("bash scripts/start_gateway.sh"),
                "处置段的启动命令须逐字保留");
        assertTrue(disposal.text().contains("taskkill"),
                "处置段的 taskkill 写命令须逐字保留（不放宽、不搬进契约）");
    }
}
