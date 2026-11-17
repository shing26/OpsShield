package com.opspilot.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.opspilot.action.Action;
import com.opspilot.action.ReadOnlyActionExtractor;
import com.opspilot.action.ReadOnlyCommandPolicy;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.ingest.IngestionRunner;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.retrieval.InMemoryIndex;
import com.opspilot.retrieval.ScoredChunk;
import com.opspilot.store.KvStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qdrant.client.QdrantClient;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * DBA 领域包的组件级行为锁（ADR-0031 / 契约 AC-5 / AC-6，确定性、不触网）。
 *
 * <p>三条断言族：① 诊断段里的每条 fenced 命令都必须过现有 L0 判据（不合格就改内容，不放宽策略）；
 * ② SOP 预热只认 pack 的止损段标记，键形状/TTL/租户分片不变；③ 抽取器只认 pack 的诊断段标记
 * 与排除词。Prompt/文案与 mock 回答仍留在底座——这是薄切片的已知限制，本测试刻意不断言它们。
 */
class DomainContentTest {

    private static final Path DBA_CHUNKS = Path.of("offline/domains/dba/corpus/chunks.jsonl");

    private static final Pattern FENCE = Pattern.compile(
            "```(bash|sql|mysql|pgsql)\\R([\\s\\S]*?)```", Pattern.MULTILINE);

    /** AC-5：诊断段内每条围栏命令都必须被现有 ReadOnlyCommandPolicy 判为 L0。 */
    @Test
    void everyDiagnosticCommandIsL0UnderExistingPolicy() throws Exception {
        List<String> checked = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(DBA_CHUNKS, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                tools.jackson.databind.JsonNode j =
                        new tools.jackson.databind.ObjectMapper().readTree(line);
                String breadcrumb = j.path("breadcrumb").asText("");
                // 只查诊断段：面包屑第二段必须命中 pack 的诊断标记，且不含排除词
                String[] parts = breadcrumb.split(" > ", -1);
                if (parts.length < 2) continue;
                String section = parts[1];
                if (DomainPack.load("dba").diagnosticMarkers().stream().noneMatch(section::contains)) {
                    continue;
                }
                if (DomainPack.load("dba").diagnosticExcludes().stream().anyMatch(section::contains)) {
                    continue;
                }
                Matcher m = FENCE.matcher(j.path("text").asText(""));
                while (m.find()) {
                    boolean statementLang = List.of("sql", "mysql", "pgsql").contains(m.group(1));
                    StringBuilder pending = new StringBuilder();
                    for (String raw : m.group(2).lines().toList()) {
                        String cmd = raw.strip();
                        if (cmd.isEmpty() || cmd.startsWith("#") || cmd.startsWith("--")) continue;
                        if (pending.length() > 0) pending.append(' ');
                        pending.append(cmd);
                        // SQL 跨行并句：未收尾的语句接着攒（与抽取器同口径）；
                        // bash 每行即一条完整命令，不留到下一行。
                        if (statementLang && !cmd.endsWith(";") && !cmd.endsWith("\\G")
                                && !cmd.endsWith("\\g")) {
                            continue;
                        }
                        String statement = pending.toString().strip();
                        pending.setLength(0);
                        checked.add(statement);
                        assertTrue(ReadOnlyCommandPolicy.isReadOnly(statement),
                                "DBA 诊断段命令必须是 L0（修内容，不放宽策略）: " + statement
                                        + " ← " + j.path("chunk_id").asText());
                    }
                    // 未收尾的 SQL 尾块也不能跳过：交策略判（fail-closed，不静默放过）
                    if (pending.length() > 0) {
                        String statement = pending.toString().strip();
                        checked.add(statement);
                        assertTrue(ReadOnlyCommandPolicy.isReadOnly(statement),
                                "DBA 诊断段未收尾命令必须是 L0: " + statement
                                        + " ← " + j.path("chunk_id").asText());
                    }
                }
            }
        }
        assertTrue(checked.size() >= 8, "DBA 诊断段至少应有 8 条可判命令，实际 " + checked.size());
    }

    /** AC-6：SOP 预热在 dba 包下认「止血与恢复」、不认旧 SRE 标记；键/TTL/租户分片不变。 */
    @Test
    void dbaSopWarmUsesPackMarkersAndKeepsKeyShape() throws Exception {
        KvStore store = mock(KvStore.class);
        when(store.mapIsEmpty("sop:tenant-internal:steps")).thenReturn(true);
        IngestionRunner runner = runner(store, dbaPack());

        runner.ensureSopWarm();

        // 止血段命中：键形状与 TTL 不变
        verify(store).deleteByPrefix("sop:tenant-internal:");
        verify(store).mapPut("sop:tenant-internal:steps", "dba-001", "止血步骤内容");
        verify(store, never()).mapPut(eq("sop:tenant-internal:steps"), eq("dba-old"), anyString());
        verify(store).expire("sop:tenant-internal:steps", java.time.Duration.ofDays(7));

        // SRE 段在 dba 包下不预热：同样内容、旧段名，不得写入
        verify(store, never()).mapPut(eq("sop:tenant-internal:steps"), eq("sre-old"), anyString());
    }

    /** AC-6：抽取器在 dba 包下抽「核查步骤」，不抽「止血与恢复」与排除词段。 */
    @Test
    void dbaExtractorUsesPackDiagnosticMarkers() {
        ReadOnlyActionExtractor extractor = new ReadOnlyActionExtractor(
                new OpsMetrics(new SimpleMeterRegistry()), dbaPack());

        ScoredChunk diagnostic = scored("dba-001::s1", "数据库值班卡 > 核查步骤 > 第一步",
                "```sql\nSHOW FULL PROCESSLIST;\n```\n");
        ScoredChunk stopLoss = scored("dba-001::s2", "数据库值班卡 > 止血与恢复",
                "```sql\nKILL 42;\n```\n");
        ScoredChunk excluded = scored("dba-001::s3", "数据库值班卡 > 核查步骤误判",
                "```sql\nSHOW FULL PROCESSLIST;\n```\n");
        ScoredChunk oldSre = scored("dba-001::s4", "数据库值班卡 > 排查步骤",
                "```sql\nSHOW FULL PROCESSLIST;\n```\n");

        List<Action> acts = extractor.extract(List.of(diagnostic, stopLoss, excluded, oldSre));
        assertEquals(1, acts.size(), "只有 pack 诊断段进入行动契约");
        assertEquals("SHOW FULL PROCESSLIST;", acts.get(0).command());
        assertEquals("dba-001::s1", acts.get(0).ref());
        assertFalse(acts.get(0).breadcrumb().contains("止血"), "写操作段不得混入");
    }

    /** SRE 段名在 dba 包下不预热的最小夹具（用 mock store 直接观测写键）。 */
    private static IngestionRunner runner(KvStore store, DomainPack pack) throws Exception {
        Path chunks = Files.createTempFile("dba-sop", ".jsonl");
        Files.writeString(chunks, String.join("\n",
                "{\"chunk_id\":\"dba-001\",\"doc_id\":\"dba-001\",\"type\":\"sop\","
                        + "\"text\":\"止血步骤内容\",\"breadcrumb\":\"数据库值班卡 > 止血与恢复\","
                        + "\"metadata\":{\"service\":\"order-service\",\"endpoint\":null,\"method\":null,"
                        + "\"error_codes\":[\"50012_DB_TIMEOUT\"],\"auth_level\":2,\"env\":\"prod\","
                        + "\"tenant\":\"tenant-internal\"}}",
                "{\"chunk_id\":\"dba-old\",\"doc_id\":\"dba-old\",\"type\":\"sop\","
                        + "\"text\":\"旧段名\",\"breadcrumb\":\"订单创建超时排查手册 > 止损操作\","
                        + "\"metadata\":{\"service\":\"order-service\",\"endpoint\":null,\"method\":null,"
                        + "\"error_codes\":[\"50012_DB_TIMEOUT\"],\"auth_level\":2,\"env\":\"prod\","
                        + "\"tenant\":\"tenant-internal\"}}",
                "{\"chunk_id\":\"sre-old\",\"doc_id\":\"sre-old\",\"type\":\"sop\","
                        + "\"text\":\"旧段名\",\"breadcrumb\":\"订单创建超时排查手册 > 止损操作\","
                        + "\"metadata\":{\"service\":\"order-service\",\"endpoint\":null,\"method\":null,"
                        + "\"error_codes\":[\"50012_DB_TIMEOUT\"],\"auth_level\":2,\"env\":\"prod\","
                        + "\"tenant\":\"tenant-internal\"}}"));
        return new IngestionRunner(emptyProvider(), emptyProvider(), store, emptyProvider(),
                mock(EmbeddingClient.class), liveProps(), mock(L2SemanticCacheService.class),
                pack, chunks.toString());
    }

    private static DomainPack dbaPack() {
        return DomainPack.load("dba");
    }

    private static ScoredChunk scored(String id, String breadcrumb, String text) {
        return new ScoredChunk(id, id.split("::")[0], "markdown_section", text, breadcrumb,
                "order-service", List.of(), 1, new ScoredChunk.Scores(0, 0, 0, 0));
    }

    private static OpsPilotProperties liveProps() {
        return new OpsPilotProperties(null, null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }
}
