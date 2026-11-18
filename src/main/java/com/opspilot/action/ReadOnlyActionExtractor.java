package com.opspilot.action;

import com.opspilot.domain.DomainPack;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.retrieval.ScoredChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 从被引用的检索 chunk 里抽出结构化只读行动契约（ADRs 0017）。
 *
 * <p><b>为什么不用 LLM 生成 JSON</b>：契约的每条命令都必须是"读"，且必须可溯源。
 * 让 LLM 写一段命令清单，等于把"会不会顺手写个 {@code KILL} 的决定权"交给生成过程——
 * 而生成过程正是整个系统里唯一没有静态可判性的部件。改成**从已引用的 chunk 原文里逐字抽**，
 * 判据变成纯词法（{@link ReadOnlyCommandPolicy}），可单测、可变异验证、可回归锁死，
 * 且天然零幻觉：命令文本就是下发过的参考内容。
 *
 * <p><b>两道闸</b>：
 * <ul>
 *   <li><b>段名闸</b>：只认标题命中注入领域包的诊断段标记、且不含其排除词的段（闭集）。
 *       这一步就把写操作段排除在外——语料里 {@code taskkill}、{@code KILL}、重启中间件
 *       都住在这类段里。段标记/排除词来自 {@link DomainPack}（ADR-0031 薄切片），
 *       本类只改段名来源，抽取与只读判定逻辑不动。</li>
 *   <li><b>命令闸</b>：段内每条命令再过 {@link ReadOnlyCommandPolicy}。判不过→丢弃并计数
 *       （{@code aiops.guard.action_commands_rejected}），不静默。</li>
 * </ul>
 *
 * <p>覆盖面是刻意的上限而非疏漏：本机辖区手册把命令写在写操作段
 * （因为那些段整体含写操作），按要求整段不进契约；PromQL 等查询语无命令形态同样不入。
 * 新增诊断段只需在语料里用领域包声明的段标记标题，无需改代码；要放宽段名规则，先补变异测试。
 */
@Component
public class ReadOnlyActionExtractor {

    /** chunk 面包屑分隔符（语料 chunks.jsonl 的 {@code " > "}，与 PromptAssembler 同源）。 */
    private static final String SEP = " > ";

    /**
     * 允许一条语句跨多物理行（无 {@code \} 续行符）的围栏语言。SQL 手册里
     * {@code SELECT …\nFROM …\nWHERE …;} 是常态，不并句的话抽出来的是半条语句——既不能用，
     * 还会把 {@code … FOR UPDATE NOWAIT} 这类行锁从句切掉，让写操作从判定里溜过去。
     */
    private static final Set<String> STATEMENT_LANGS =
            Set.of("sql", "mysql", "pgsql", "plsql", "tsql", "cql", "hql");

    private final OpsMetrics metrics;
    private final DomainPack domain;

    /** 生产构造器（ADR-0031 D3）：段名判据由 {@code DomainRegistry} 供给的领域包决定。 */
    @Autowired
    public ReadOnlyActionExtractor(OpsMetrics metrics, DomainPack domain) {
        this.metrics = metrics;
        this.domain = domain;
    }

    /** test-only / 兼容构造器：委托到 {@link DomainPack#sre()}，生产 bean 走上面的注入构造器。 */
    public ReadOnlyActionExtractor(OpsMetrics metrics) {
        this(metrics, DomainPack.sre());
    }

    /**
     * 抽契约。入参即本次实际引用（下发给 LLM 的同一份 top-k），因此租户/密级过滤早已发生——
     * 契约不再设访问闸，也没有可设的（命令文本属于已下发内容）。
     *
     * @return 按 chunk 顺序、组内重编号的只读行动清单；没有任何可抽内容时返回空表
     */
    public List<Action> extract(List<ScoredChunk> chunks) {
        List<Action> out = new ArrayList<>();
        for (ScoredChunk c : chunks) {
            String section = sectionOf(c.breadcrumb());
            if (section.isEmpty()) {
                continue;
            }
            String step = stepOf(c.breadcrumb(), section);
            for (Fenced fenced : fencedBlocks(c.text())) {
                for (String command : commands(fenced)) {
                    if (ReadOnlyCommandPolicy.isReadOnly(command)) {
                        out.add(new Action(0, step, command, fenced.lang().isEmpty() ? null : fenced.lang(),
                                c.chunkId(), c.breadcrumb(), c.service()));
                    } else {
                        // 静默丢弃会让"某段一条建议都没有"与"压根没抽"无法区分，故显式计数
                        metrics.actionCommandRejected();
                    }
                }
            }
        }
        for (int i = 0; i < out.size(); i++) {
            Action a = out.get(i);
            out.set(i, new Action(i + 1, a.step(), a.command(), a.lang(), a.ref(), a.breadcrumb(), a.service()));
        }
        return out;
    }

    /**
     * 选槽保位用（command-yield-accept）：该 chunk 是否住着"能产行动契约的诊断叶"——
     * 面包屑第二段命中领域包诊断段标记且不含排除词，且围栏块里**至少一条**命令通过 L0。
     *
     * <p>刻意只复用 {@link #sectionOf} / {@link #fencedBlocks} / {@link #commands} 这三段既有判据，
     * 不改 {@link #extract} 行为：选槽用的闸与出契约用的闸必须是同一个，否则"选进来却抽不出"
     * 或"没选却可抽"都会让 Top-3 的引用组成说服不了人。本方法不计数判拒——那只属于真正
     * 出契约的路径，选槽只是看一眼候选。
     *
     * <p>可见性：契约写"包可见"，但调用方在 {@code com.opspilot.retrieval} 包，Java 包可见
     * 跨包不可调用；为兑现"供选槽判断"的用途，本方法必须是 {@code public static}。
     */
    public static boolean isDiagnosticActionLeaf(ScoredChunk chunk, DomainPack domain) {
        if (chunk == null || domain == null) {
            return false;
        }
        String[] parts = chunk.breadcrumb() == null ? new String[0]
                : chunk.breadcrumb().split(SEP, -1);
        if (parts.length < 2) {
            return false;
        }
        String section = parts[1].trim();
        if (domain.diagnosticMarkers().stream().noneMatch(section::contains)) {
            return false;
        }
        for (String ex : domain.diagnosticExcludes()) {
            if (section.contains(ex)) {
                return false;
            }
        }
        for (Fenced fenced : fencedBlocks(chunk.text())) {
            for (String command : commands(fenced)) {
                if (ReadOnlyCommandPolicy.isReadOnly(command)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 面包屑 {@code doc > 段 > 小节} 的「段」（第二段）；非诊断段返回空串。 */
    private String sectionOf(String breadcrumb) {
        if (breadcrumb == null) {
            return "";
        }
        String[] parts = breadcrumb.split(" > ", -1);
        if (parts.length < 2) {
            return "";
        }
        String section = parts[1].trim();
        if (domain.diagnosticMarkers().stream().noneMatch(section::contains)) {
            return "";
        }
        for (String ex : domain.diagnosticExcludes()) {
            if (section.contains(ex)) {
                return "";
            }
        }
        return section;
    }

    /** 步骤名：小节标题（有 {@code 第N步} 之类的 h3 才有信息量），否则回落到段名。 */
    private static String stepOf(String breadcrumb, String section) {
        if (breadcrumb == null) {
            return section;
        }
        String[] parts = breadcrumb.split(" > ", -1);
        return parts.length >= 3 ? parts[parts.length - 1].trim() : section;
    }

    /** 一条围栏块：语言 + 其中的命令候选（已合并续行、剥注释）。 */
    private record Fenced(String lang, List<String> commands) {}

    private static List<Fenced> fencedBlocks(String text) {
        List<Fenced> out = new ArrayList<>();
        List<String> lines = text.lines().toList();
        String lang = null;
        List<String> body = null;
        for (String line : lines) {
            String s = line.strip();
            if (lang == null) {
                int ticks = fenceTicks(s);
                if (ticks == 3) {
                    lang = s.substring(3).strip();
                    body = new ArrayList<>();
                }
                continue;
            }
            if (s.equals("```")) {
                out.add(new Fenced(lang, commands(new Fenced(lang, body))));
                lang = null;
                body = null;
            } else {
                body.add(line);
            }
        }
        // 未闭合围栏：正文残缺（分块切坏的 chunk），不猜——丢弃
        return out;
    }

    /** 开围栏的刻度数：``` 即 3；更多刻度是嵌套/字面量演示，不当代码块（也防止把示例文本当命令）。 */
    private static int fenceTicks(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == '`') {
            n++;
        }
        return n;
    }

    private static List<String> commands(Fenced fenced) {
        boolean byTerminator = STATEMENT_LANGS.contains(fenced.lang().toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        StringBuilder logical = new StringBuilder();
        for (String raw : fenced.commands()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("--")) {
                continue;   // 注释行 / 空行
            }
            if (byTerminator) {
                line = dropAfterTerminator(line);
                if (line.isEmpty()) {
                    continue;   // 收尾符之后只剩行内注释：本行没有语句增量
                }
            }
            if (logical.length() > 0) {
                logical.append(' ');
            }
            logical.append(line);
            if (line.endsWith("\\")) {
                logical.setLength(logical.length() - 1);   // 续行：接上下一行
                continue;
            }
            if (byTerminator && !statementClosed(logical)) {
                continue;   // 语句没收尾（SQL 跨行）：接着攒
            }
            out.add(logical.toString().strip());
            logical.setLength(0);
        }
        if (logical.length() > 0) {
            out.add(logical.toString().strip());   // 围栏结束都没收尾：按原样交出，交给策略判
        }
        return out;
    }

    /** SQL 语句收尾：{@code ;}，或 MySQL 的 {@code \G}/{@code \g}（竖版输出）。 */
    private static boolean statementClosed(StringBuilder sql) {
        String s = sql.toString().strip();
        return s.endsWith(";") || s.endsWith("\\G") || s.endsWith("\\g");
    }

    /**
     * 语句收尾符之后的尾随文本（行内注释）丢掉：手册里
     * {@code EXPLAIN SELECT …;  -- 对疑似 SQL 执行，重点看 key 与 rows} 是常态写法。
     *
     * <p>不丢的后果不是"少给一条"而是**整条判拒**：策略按 {@code ;} 切段，注释文本会变成
     * 一段"命令"，首 token 是 {@code --} 不在工具表里——手册里最有用的那条 EXPLAIN 就这么
     * 没了（rb-001 实测踩到）。只切收尾符之后，语句体内的注释保持原样（词法层不解析字符串
     * 字面量，与 {@link ReadOnlyCommandPolicy} 已知的保守偏差同源）。
     */
    private static String dropAfterTerminator(String line) {
        int end = -1;   // 收尾符结束位置（不含）；`\G` 是两个字符，不能按 index+1 算
        for (String t : List.of(";", "\\G", "\\g")) {
            int i = line.lastIndexOf(t);
            if (i >= 0 && i + t.length() > end) {
                end = i + t.length();
            }
        }
        return end < 0 || line.substring(end).isBlank() ? line : line.substring(0, end);
    }
}
