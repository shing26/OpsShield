package com.opspilot.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.retrieval.ScoredChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 只读行动契约抽取器的回归锁（ADRs 0017）。
 *
 * <p>三层验证：
 * <ul>
 *   <li><b>单元</b>：段名闸（含「排查」且不含「陷阱/误判」）、续行合并、未闭合围栏丢弃、
 *       编号/步骤名/语言字段；</li>
 *   <li><b>可观测性</b>：被命令策略判拒的命令必须恰好让拒绝计数器 +1——静默丢弃会让
 *       "某段一条建议都没有"与"压根没抽到"无法区分；</li>
 *   <li><b>活语料扫描</b>：{@code offline/corpus/chunks.jsonl} 全量 448 chunk 过一遍
 *       真实抽取，逐条断言「命令文本在来源 chunk 里逐字存在」（零幻觉锁）+ 段名闸没漏。</li>
 * </ul>
 */
class ReadOnlyActionExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static OpsMetrics metrics;

    @BeforeAll
    static void setUp() {
        metrics = new OpsMetrics(new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("诊断段抽出契约：编号/步骤/语言/溯源四要素齐备")
    void diagnosticSectionYieldsContract() {
        ScoredChunk c = chunk("rb-001::s1", "rb-001", "order-service",
                "订单创建超时排查手册 > 排查步骤 > 第一步：确认是数据库慢",
                "[手册 > 排查步骤 > 第一步]\n\n看连接池。\n\n```sql\n"
                        + "SELECT * FROM information_schema.processlist ORDER BY time DESC LIMIT 20;\n```\n\n"
                        + "```bash\ncurl -s localhost:8080/actuator/health | jq\n```\n");
        List<Action> acts = extractor().extract(List.of(c));

        assertEquals(2, acts.size(), "两个围栏各出一条命令");
        Action first = acts.get(0);
        assertEquals(1, first.n());
        assertEquals("第一步：确认是数据库慢", first.step(), "步骤名取小节标题");
        assertEquals("sql", first.lang());
        assertEquals("rb-001::s1", first.ref(), "ref 与参考来源同一命名空间");
        assertEquals("订单创建超时排查手册 > 排查步骤 > 第一步：确认是数据库慢", first.breadcrumb());
        assertEquals("order-service", first.service());
        Action second = acts.get(1);
        assertEquals(2, second.n(), "编号跨块连续重编");
        assertEquals("bash", second.lang());
        assertTrue(second.command().contains("curl -s localhost:8080/actuator/health"));
    }

    @Test
    @DisplayName("围栏无语言标记时 lang 为 null（不猜语言）")
    void fenceWithoutLangHasNullLang() {
        ScoredChunk c = chunk("rb-008::s2", "rb-008", "order-service",
                "限流与熔断排查手册 > 排查步骤 > 关键判断：真过载还是规则丢失",
                "```\njstat -gcutil <pid> 1000 5\n```\n");
        List<Action> acts = extractor().extract(List.of(c));
        assertEquals(1, acts.size());
        assertEquals(null, acts.get(0).lang(), "无语言标记不许默认成 bash");
        assertEquals("关键判断：真过载还是规则丢失", acts.get(0).step(), "步骤名取小节标题");
    }

    @Test
    @DisplayName("写操作段整段不进契约：止损操作/修复措施/处置/常见误判/已知陷阱")
    void writeSectionsYieldNoContract() {
        List.of(
                "下单库超时 > 止损操作",
                "订单创建接口大面积超时复盘 > 修复措施",
                "起不来 / 连不上 > 处置",
                "下单库超时 > 常见误判",
                "集群内 DNS 解析抖动 > 已知陷阱").forEach(bc -> {
            ScoredChunk c = chunk("x::s", "x", "order-service", bc,
                    "[面包屑]\n\n```bash\ntaskkill //F //PID 1234\n```\n");
            assertTrue(extractor().extract(List.of(c)).isEmpty(),
                    "写操作/警告段必须整段不进契约: " + bc);
        });
    }

    @Test
    @DisplayName("段名闸只看第二段：标题含'排查'但段名不含，不放行")
    void sectionGateIgnoresDocTitle() {
        // rb-008 的「关键判断：真过载还是规则丢失」里确实住着诊断命令，但段名不含「排查」
        // → 整段不进契约。这是刻意的保守上限（ADRs 0017「段名闸的代价」）：宁可少给一段，
        // 不可把"排除词没覆盖到的写操作段"放进来。
        ScoredChunk t = chunk("rb-008::s2", "rb-008", "order-service",
                "限流与熔断排查手册 > 关键判断：真过载还是规则丢失",
                "```bash\ncurl -s localhost:8719/sentinel/order-service/flow rules | jq '.[].count'\n```\n");
        assertTrue(extractor().extract(List.of(t)).isEmpty(), "标题含「排查」不算诊断段，段名才算");

        // 面包屑只有一段（没有段名信息）时同样不猜
        ScoredChunk one = chunk("rb-101::s3", "rb-101", "opspilot",
                "判\"这个端口上是谁\"的固定动作", "```bash\nnetstat -ano | grep :8081\n```\n");
        assertTrue(extractor().extract(List.of(one)).isEmpty(), "单段面包屑没有段名，不猜");
    }

    @Test
    @DisplayName("续行合并：\\ 结尾的两行当成一条命令")
    void continuationLinesAreJoined() {
        ScoredChunk c = chunk("rb-014::s4", "rb-014", "payment-service",
                "证书过期排查手册 > 排查步骤 > 第一步：查证书",
                "```bash\necho | openssl s_client -connect gateway.alipay.example:443 \\\n"
                        + "  2>/dev/null | openssl x509 -noout -dates\n```\n");
        List<Action> acts = extractor().extract(List.of(c));
        assertEquals(1, acts.size(), "续行不许拆成两条");
        String cmd = acts.get(0).command();
        assertTrue(cmd.contains("openssl x509 -noout -dates"), cmd);
        assertTrue(cmd.indexOf("openssl s_client") < cmd.indexOf("openssl x509"), "拼接顺序保持原样");
    }

    @Test
    @DisplayName("SQL 跨行并句：SELECT/FROM/WHERE 合成一条，FOR UPDATE 那条例外被拒")
    void multilineSqlIsJoinedIntoOneStatement() {
        ScoredChunk c = chunk("rb-002::s7", "rb-002", "order-service",
                "数据库死锁排查手册 > 排查步骤 > 第二步：看锁等待",
                "```sql\nSELECT sku_id, version FROM t_inventory\n"
                        + "WHERE sku_id IN (/* 死锁日志中的主键 */) FOR UPDATE NOWAIT;\n"
                        + "SELECT order_id FROM t_order WHERE order_id = ?;\n```\n");
        List<Action> acts = extractor().extract(List.of(c));

        assertEquals(1, acts.size(), "并句后只有不带行锁的那条留下");
        assertEquals("SELECT order_id FROM t_order WHERE order_id = ?;", acts.get(0).command(),
                "跨行语句必须原样并成一条（半条语句既不能用，还会切掉 FOR UPDATE 从句）");
    }

    @Test
    @DisplayName("SQL 行内注释在收尾符之后：剥掉，否则整条判拒")
    void trailingSqlCommentAfterTerminatorIsStripped() {
        // rb-001 原文形态（2026-10-10 实测踩到）：不剥的话策略按 ; 切段，
        // 注释文本变成首 token 为 -- 的"命令"，手册里最有用的 EXPLAIN 整条判拒
        ScoredChunk c = chunk("rb-001::s2", "rb-001", "order-service",
                "订单创建超时排查手册 > 排查步骤 > 第二步：定位慢 SQL",
                "```sql\nSHOW FULL PROCESSLIST;\n"
                        + "EXPLAIN SELECT ... ;  -- 对疑似 SQL 执行，重点看 key 与 rows\n```\n");
        List<Action> acts = extractor().extract(List.of(c));

        assertEquals(2, acts.size(), "两条语句都该留下: " + acts);
        assertEquals("EXPLAIN SELECT ... ;", acts.get(1).command(), "行内注释必须剥掉");
    }

    @Test
    @DisplayName("未闭合围栏与注释行不参与抽取")
    void unclosedFenceAndCommentsDropped() {
        ScoredChunk c = chunk("rb-011::s5", "rb-011", "user-service",
                "JVM GC 停顿排查手册 > 排查步骤 > 第一步：看 GC 日志",
                "```bash\n# 先看 Full GC\ngrep -E \"Full GC\" /var/log/app/gc.log | tail -20\n"
                        + "```\n\n```sql\n-- 下面这条被分块切断了\nSELECT 1\n");
        List<Action> acts = extractor().extract(List.of(c));
        assertEquals(1, acts.size(), "未闭合围栏（正文残缺）整块丢弃，不许猜一半");
        assertTrue(acts.get(0).command().startsWith("grep -E"), acts.get(0).command());
    }

    @Test
    @DisplayName("判拒即计数：不静默（aiops.guard.action_commands_rejected）")
    void rejectedCommandIsCountedNotSilent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpsMetrics m = new OpsMetrics(registry);
        ScoredChunk c = chunk("rb-002::s6", "rb-002", "order-service",
                "数据库死锁排查手册 > 排查步骤 > 第二步：必要时人工介入",
                "```sql\nSHOW ENGINE INNODB STATUS\\G\nKILL 42;\n```\n");
        List<Action> acts = new ReadOnlyActionExtractor(m).extract(List.of(c));

        assertEquals(1, acts.size(), "只读那条留下");
        assertTrue(acts.get(0).command().startsWith("SHOW ENGINE"), acts.get(0).command());
        assertEquals(1.0, counter(registry, "aiops.guard.action_commands_rejected"),
                "KILL 42 被命令闸判拒，必须 +1（否则无法区分'没有'与'抽不到'）");
    }

    // ────────────────────────── 活语料扫描 ──────────────────────────

    @Test
    @DisplayName("447-448 chunk 全量抽取：零幻觉锁 + 段名闸闭合")
    void liveCorpusSweepIsVerbatimAndSectionSafe() throws Exception {
        Path file = Path.of("offline/corpus/chunks.jsonl");
        assertTrue(Files.exists(file), "活语料必须存在（相对模块根路径）");

        List<ScoredChunk> chunks = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode j = MAPPER.readTree(line);
            chunks.add(new ScoredChunk(j.get("chunk_id").asText(), j.get("doc_id").asText(),
                    j.get("type").asText(), j.get("text").asText(),
                    j.get("breadcrumb").asText(),
                    j.path("metadata").path("service").asText(""),
                    List.of(), 1, new ScoredChunk.Scores(0, 0, 0, 0)));
        }
        assertTrue(chunks.size() >= 440,
                "chunks.jsonl 只读到 " + chunks.size() + " 行，语料疑似被截断/换名（2026-10-10 为 448 行）");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpsMetrics m = new OpsMetrics(registry);
        List<Action> acts = new ReadOnlyActionExtractor(m).extract(chunks);
        double rejected = counter(registry, "aiops.guard.action_commands_rejected");

        // (a) 段名闸闭合：每条契约的面包屑第二段必含「排查」且不含「陷阱/误判」
        for (Action a : acts) {
            String[] parts = a.breadcrumb().split(" > ", -1);
            assertTrue(parts.length >= 2, "面包屑至少两段: " + a.breadcrumb());
            assertTrue(parts[1].contains("排查"), "非诊断段漏进契约: " + a.breadcrumb());
            assertTrue(!parts[1].contains("陷阱") && !parts[1].contains("误判"),
                    "警告段漏进契约: " + a.breadcrumb());
        }

        // (b) 零幻觉锁：命令文本必须能在来源 chunk 原文里逐字找到（归一化后）
        //     ——契约不是"生成"出来的，是"摘"出来的，这句话因此可静态判定。
        //     归一化口径在本测试内独立实现（续行并一行、丢注释/空行、空白折叠），
        //     刻意不复用抽取器代码：换了实现要对得上，才锁得住"逐字"。
        for (Action a : acts) {
            String src = chunks.stream().filter(c -> c.chunkId().equals(a.ref())).findFirst()
                    .orElseThrow().text();
            String hay = normalizedSource(src);
            String needle = Pattern.compile("\\s+").matcher(a.command()).replaceAll(" ").strip();
            assertTrue(hay.contains(needle),
                    "命令不是来源 chunk 的原文（幻觉）: " + a.command() + " ← " + a.ref());
        }

        // (c)(d) 覆盖面上下限都锁死，并另加一条"写操作段黑名单"独立断言。
        //      取证（2026-10-10，448 chunk / 闭合围栏 70 块 / 命令候选 142 条；段名闸内 103 chunk
        //      中 37 chunk 带围栏，38 块 / 候选 49 条）：抽出 41 条、判拒 8 条（rb-002 的
        //      FOR UPDATE 1、rb-004 的 sh mqadmin 2、rb-007 的伪代码 2、rb-009 的 PromQL 与
        //      kubectl exec … java 2、rb-013 的 POST 1）。段名闸全关时可抽数会向 142 逼近，
        //      故上限留足增长空间但仍远低于"闸全开"；下限略低于当前值，防链路退步却全绿。
        //      注意这是**抽取能力**的账（喂全部闸内 chunk），不是单次请求产额：线上一次请求
        //      只喂被引用的 top-3（final-top-k: 3），实测 6/11 形状出非空契约——两个口径的
        //      差别正是 ADR-0017 与 OPS §5 登记的软上限，别拿这个 41 当产额读。
        assertTrue(acts.size() >= 38, "可抽命令数低于历史基线（抽取链路疑似退步）: " + acts.size());
        assertTrue(acts.size() <= 120, "可抽命令数异常膨胀（段名闸疑似被放开）: " + acts.size());
        assertTrue(rejected >= 5, "判拒数低于历史基线——策略可能变恒真或计数器没接: " + rejected);
        // 写操作/警告段的黑名单独立断言：只靠白名单一旦被改成黑名单，这条必红
        for (Action a : acts) {
            for (String bad : List.of("止损", "修复", "处置", "误判", "陷阱")) {
                assertTrue(!a.breadcrumb().contains(bad),
                        "写操作/警告段混进契约: " + a.breadcrumb());
            }
        }
        System.out.println("SWEEP acts=" + acts.size() + " rejected=" + rejected
                + " chunks=" + chunks.size());
    }

    // ────────────────────────── 辅助 ──────────────────────────

    /** 原文归一化：{@code \} 续行并成一行、丢注释行与空行、空白折叠成单空格（围栏标记保留）。 */
    private static String normalizedSource(String text) {
        Pattern ws = Pattern.compile("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String line : text.replace("\\\n", " ").lines().toList()) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("--")) {
                continue;
            }
            sb.append(ws.matcher(s).replaceAll(" ")).append(' ');
        }
        return sb.toString().strip();
    }

    private static double counter(SimpleMeterRegistry registry, String name) {
        return registry.find(name).counter() == null ? 0.0
                : registry.find(name).counter().count();
    }

    private static ReadOnlyActionExtractor extractor() {
        return new ReadOnlyActionExtractor(metrics);
    }

    private static ScoredChunk chunk(String chunkId, String docId, String service,
                                     String breadcrumb, String text) {
        return new ScoredChunk(chunkId, docId, "runbook", text, breadcrumb, service,
                List.of(), 1, new ScoredChunk.Scores(0, 0, 0, 0));
    }
}
