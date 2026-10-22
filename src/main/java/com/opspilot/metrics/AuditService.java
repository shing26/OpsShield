package com.opspilot.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.auth.UserContext;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 合规审计事件（P4，ADR-0005）：写专用 logger（logback-spring.xml 挂
 * logs/audit.jsonl，gitignore+14 天滚动）。回答"谁在何时查过什么、答案最高触到
 * 哪个密级"——权限引擎层的可核查闭环。query 明文截断（问题文本属团队内部，不存
 * 答案全文以控制体积与敏感度）。
 */
@Component
public class AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("opspilot.audit");
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 进程内有界事件环（Ops Console 数据流面，ADR-0009）：writeEvent 是全部审计事件的
     * 唯一必经出口，在此挂 200 条 ring buffer ——**不改落盘行为**。有界即有轮转，
     * 轮转必有丢失：recentSince 以 truncated 显式告知（客户端游标早于最老驻留事件，
     * 或服务重启游标越过 maxSeq 的"回绕"），禁止静默空洞。seq 全局单调，与 ts 无关
     * （同毫秒乱序是文件行的既实现实，面板消费必须用 seq）。
     */
    private static final int RING_CAP = 200;
    private final java.util.ArrayDeque<Map<String, Object>> ring = new java.util.ArrayDeque<>();
    private long seq;

    /**
     * 审计哈希链（G1，2026-10-22；门禁 9）：每条行带 {@code prev_hash} 与 {@code hash}。
     *
     * <p>为什么需要：审计文件是**可编辑文本**——攻击者或误操作改一行、删一行，单看内容
     * 无法发现。哈希链把"改一行"变成"从该行起所有 hash 都对不上"，配合 {@code scripts/verify_audit_chain.py}
     * 可在事后（或发布前）证明未被篡改。它不阻止删除**尾部**（没有外部锚点时无法自证），
     * 故仍需异地/只追加存储；这里只解决"内容是否被改"。
     *
     * <p>hash 覆盖范围：{@code prev_hash + "\n" + 去掉 hash 字段后的整行 JSON}。
     * 用"原始前缀"而不是重新序列化对象，避免 Python/Java 对 Unicode/浮点格式的差异导致假红。
     */
    private static final String GENESIS = "GENESIS";
    private volatile String prevHash = GENESIS;
    private boolean persistChain;
    private Path chainHead = Path.of("logs", ".audit-head");
    private Path auditFile = Path.of("logs", "audit.jsonl");

    /** 自 lastSeq 以来的增量事件；truncated=有丢失（轮转或重启）。 */
    public synchronized RecentResult recentSince(long lastSeq, int limit) {
        long maxSeq = seq;
        boolean restart = lastSeq > maxSeq; // 客户端游标超前：服务重启（seq 归零）
        if (restart) {
            lastSeq = 0;                    // 当前驻留事件全给，游标不得卡死
        }
        boolean truncated = restart || (!ring.isEmpty() && lastSeq < seq - ring.size());
        int capped = Math.max(1, Math.min(limit, RING_CAP));
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> ev : ring) {
            long s = ((Number) ev.get("seq")).longValue();
            if (s > lastSeq) {
                out.add(ev);
                if (out.size() >= capped) break;
            }
        }
        return new RecentResult(java.util.List.copyOf(out), maxSeq, truncated);
    }

    public record RecentResult(java.util.List<Map<String, Object>> events, long maxSeq, boolean truncated) {}

    /**
     * 合规业务事件（**单一入口**：新增字段一律加参，不加少参重载）。
     * 本方法 2026-09-16 前有三档重载（10/11/12 参），source 缺口正藏在"调用方走了哪一档"里——
     * 重载会让新字段在旧调用点上静默缺省，落进日志就是"看着有、其实没有"。
     *
     * @param via    调用面来源（"sse"/"openai"/"search-api"），合规视角区分谁经哪个协议面进来；
     *               旧日志无此字段，消费端（daily_usage）按 dict .get 解析天然向后兼容。
     * @param source 业务来源标记（ADR-0004 的 manual|alert，DTO 侧已 @Pattern 收口）；
     *               与 via 正交——via 答"从哪个协议面进来"，source 答"这是人工排障还是告警触发"。
     *               空值归一为 manual：审计行永不留空，避免"没来源"和"人工"两种含义混淆。
     *               注意它是**客户端自述元数据**，不参与鉴权/配额，只作可观测与风暴窗口分档，
     *               不可当来源溯源的证据使用。
     * @param srcTenant 回放/共享载荷的来源租户（QA P1-2 补"命中来源"宣称缺口）。仅缓存/Single-Flight
     *               回放路径携带（正常恒等于请求者租户；不等即 P0-1 类复发的可 grep 告警面），
     *               普通生成请求传 null 不落字段，避免全量噪音。
     * @param verbatimMasked 出口掩码句数（生成质量包 Q2=C），仅 &gt;0 时携带——"谁试图逐字导出、
     *               被拦了几句"的事后可查面。
     * @param stages 单请求分段耗时（G2），仅 chat 路径携带（{@code /search}、鉴权、管理面传 null）——
     *               与 src_tenant/verbatim_masked 同约定：null 即不落字段，避免全量噪音。
     * @param degradeLevel 本次请求观察到的降级档位（{@code L0|L1|L2}）。**为什么必须要它**：`mode`
     *               字段有两个来源——L1 降级（编排按档位传 {@code es_only}）与检索腿超时
     *               （{@code HybridSearchService} 的 {@code effectiveMode}），两者落进审计行是同一个
     *               字符串，"这次到底是不是负载触发的降级"从 `mode` 单字段答不出来（2026-09-28 复核
     *               实测）。显式档位字段把两种解释分开；`/search` 不查状态机，故传 null 不落字段。
     */
    public void log(UserContext user, String kind, String via, String source, String query, String fingerprint,
                    String cacheHit, String mode, boolean refused, int maxResultAuthLevel, long tookMs,
                    String srcTenant, Integer verbatimMasked, StageTimings stages, String degradeLevel) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", kind);
        ev.put("via", via);
        ev.put("source", source == null || source.isBlank() ? "manual" : trunc(source, 32));
        ev.put("sub", user == null ? "" : trunc(user.sub(), 64));
        ev.put("tenant", user == null ? "" : trunc(user.tenantId(), 64));
        ev.put("level", user == null ? 0 : user.authLevel());
        ev.put("q", trunc(query, 200));
        ev.put("fp", trunc(fingerprint, 64));
        ev.put("cache_hit", trunc(cacheHit, 16));
        ev.put("mode", trunc(mode, 16));
        if (degradeLevel != null) ev.put("degrade_level", degradeLevel);
        ev.put("refused", refused);
        ev.put("max_level", maxResultAuthLevel);
        ev.put("took_ms", tookMs);
        if (srcTenant != null) ev.put("src_tenant", srcTenant);
        if (verbatimMasked != null) ev.put("verbatim_masked", verbatimMasked);
        if (stages != null) ev.put("stage_ms", stages.toMap());
        writeEvent(ev);
    }

    /**
     * 鉴权拒绝留痕（QA P1-2：此前"每请求一行"只覆盖成功业务请求——401/403/登录失败全是黑洞，
     * 攻击探测零可查）。sub 仅在 token 可解析时携带（未知/畸形凭证不编造身份）。
     *
     * @param outcome denied（守卫面 401）/ login_failed（登录面，含暴力锁定）
     */
    public void logAuthDenied(String sub, String path, String outcome, String reason) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", "auth");
        ev.put("outcome", outcome);
        if (sub != null && !sub.isBlank()) ev.put("sub", trunc(sub, 64));
        ev.put("path", trunc(path, 128));
        ev.put("reason", trunc(reason, 64));
        writeEvent(ev);
    }

    /** 管理面动作留痕（QA P1-2）：破坏性端点的成功与被拒都必须可回溯。 */
    public void logAdmin(UserContext user, String action, String outcome, String detail) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", "admin");
        ev.put("sub", user == null ? "" : user.sub());
        ev.put("tenant", user == null ? "" : user.tenantId());
        ev.put("level", user == null ? 0 : user.authLevel());
        ev.put("role", user == null ? "" : user.role());
        ev.put("action", action);
        ev.put("outcome", outcome);
        if (detail != null) ev.put("detail", trunc(detail, 200));
        writeEvent(ev);
    }

    /** 请求体校验失败留痕（QA 小周 P2-2：400 既无信息又无痕，用户改请求如坠迷雾）。 */
    public void logInvalid(UserContext user, String path, String message) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", "invalid");
        ev.put("sub", user == null ? "" : user.sub());
        ev.put("tenant", user == null ? "" : user.tenantId());
        ev.put("level", user == null ? 0 : user.authLevel());
        ev.put("path", path);
        if (message != null) ev.put("msg", trunc(message, 200));
        writeEvent(ev);
    }

    /**
     * 答案反馈留痕（闭环前置）：人把"这个答案不对"告诉系统的落点。
     *
     * 为什么用**辅助写入器**而不扩展上面那条 13 参 `log()`：那条方法的纪律是"新增字段一律加参"，
     * 但反馈是**另一种事件**——它没有 cache_hit / mode / took_ms / max_level 可言，硬塞进去会让
     * 那条记录长出一排无意义的默认值。本方法属 `logAuthDenied`/`logAdmin`/`logInvalid` 同一族：
     * 独立 schema 的辅助写入器，共用 `writeEvent` 这唯一出口（故同样自动获得 seq 与 request_id）。
     *
     * 为什么按 fingerprint 归档：见 {@code FeedbackRequest} 的类注释——它标识"问题"而非"某次生成"，
     * 而"复盘→知识回灌"要沉淀的正是知识（问题）而非生成。
     *
     * @param verdict `up` | `down`（DTO 侧 @Pattern 已收口）
     * @param note    可选补充说明，落盘前再截 200 字（DTO 已限长，这里是纵深防御）
     */
    public void logFeedback(UserContext user, String fingerprint, String verdict, String note) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", "feedback");
        ev.put("sub", user == null ? "" : user.sub());
        ev.put("tenant", user == null ? "" : user.tenantId());
        ev.put("level", user == null ? 0 : user.authLevel());
        ev.put("fp", fingerprint);
        ev.put("verdict", verdict);
        if (note != null && !note.isBlank()) {
            ev.put("note", trunc(note, 200));
        }
        writeEvent(ev);
    }

    /**
     * 降级档位转移留痕（2026-09-28，OP-A5）：给"降级发生了什么"一个可复核的事件序列。
     *
     * 为什么单独成事件而不只加 `degrade_level` 字段：字段答的是"**这次请求时**档位是几"——
     * 它靠请求密度间接反推切换时刻，无请求的区间里切换会被漏掉，而且看不出"何时切、为何切"。
     * 转移事件直接给出 {@code from → to + cause}，离线时间线（读 14 天滚动的 logs/audit.jsonl）
     * 与压测结论都可据此复核。注意**量具边界**：档位是**拉模型**（无独立定时器），转移在
     * 计数器变化（enter/exit/llmFailure/llmSuccess/manual）与 current() 被调用时被观察到；
     * 而 current() 在每请求与 `/state` 轮询时都会调用，故实验与面板在场的场景下不会漏。
     *
     * @param from  切换前档位（{@code L0|L1|L2}）
     * @param to    切换后档位
     * @param cause 有限词表：{@code inflight}（在途超阈→L1）/ {@code llm_failure}（连续失败达阈→L2）/
     *              {@code load_subsided}（负载回落→L0）/ {@code cooldown_expired}（冷却到期半开→L0）/
     *              {@code manual}（人工锁定）/ {@code manual_clear}（解除锁定）
     */
    public void logDegradeTransition(String from, String to, String cause) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", "degrade_transition");
        ev.put("from", from);
        ev.put("to", to);
        ev.put("cause", cause);
        writeEvent(ev);
    }

    /**
     * 失败请求留痕（2026-10-22，G0）：此前只有成功/拒答/澄清路径写审计——
     * "欠费、超时、后段异常"这些最需要证据的时刻恰好是黑洞（审计 only-on-success 的副作用）。
     * 本事件与 chat 行共享同一 writeEvent 出口（request_id/trace_id/seq/ring 自动继承），
     * 但 schema 独立：失败没有 cache_hit/mode/max_level 可言，硬塞一排默认值会污染可读性。
     *
     * <p>为什么带 err_class 与截断后的 err_msg：值班要区分"上游 4xx/429/超时/本地 NPE"，
     * 而完整堆栈已由编排层 logWarn 落到 app.log；审计行只留可聚合的摘要，不复制长文本。
     *
     * @param error 编排层捕获的异常；调用方保证非 null
     * @param degradeLevel 失败时观察到的档位（L0/L1/L2），可为 null（未观察）
     */
    public void logChatFailed(UserContext user, String via, String source, String query, String fingerprint,
                              Throwable error, String degradeLevel) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", "chat_failed");
        ev.put("via", trunc(via, 32));
        ev.put("source", source == null || source.isBlank() ? "manual" : trunc(source, 32));
        ev.put("sub", user == null ? "" : trunc(user.sub(), 64));
        ev.put("tenant", user == null ? "" : trunc(user.tenantId(), 64));
        ev.put("level", user == null ? 0 : user.authLevel());
        ev.put("q", trunc(query, 200));
        if (fingerprint != null && !fingerprint.isBlank()) ev.put("fp", trunc(fingerprint, 64));
        ev.put("err_class", error == null ? "unknown" : trunc(error.getClass().getSimpleName(), 64));
        ev.put("err_msg", error == null ? "" : trunc(error.getMessage(), 200));
        // G1：失败/中断路径明确标注"没有完整交付"——回放与审计对账时不必靠 err_class 猜。
        ev.put("delivered", false);
        if (degradeLevel != null) ev.put("degrade_level", degradeLevel);
        writeEvent(ev);
    }

    /** 所有入审计的字符串统一收口：字段级截断只在这里定义，避免"某个字段忘了截"再次出现。 */
    static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Spring 启动时接续上一次的链头；单测直接 new 时不做文件 I/O（避免污染仓库 logs/）。 */
    @PostConstruct
    void initChain() {
        persistChain = true;
        chainHead = Path.of(System.getProperty("opspilot.audit.head", "logs/.audit-head"));
        auditFile = Path.of(System.getProperty("opspilot.audit.file", "logs/audit.jsonl"));
        try {
            String headHash = null;
            if (Files.exists(chainHead)) {
                String head = Files.readString(chainHead, StandardCharsets.UTF_8).trim();
                if (!head.isBlank()) headHash = head;
            }
            // 以**审计文件最后一行**为准，head 只作补充：异步 appender 可能让 head 比已落盘行新，
            // 若盲目接 head，重启后的新行会指向一个不存在的行（校验器必然报断链）。旧格式行没有
            // hash，无法接续，明确从 GENESIS 开新链——不假装旧行已被链覆盖。
            String last = lastLine(auditFile);
            String lastHash = null;
            if (last != null) {
                Object parsed = mapper.readValue(last, Map.class);
                Object h = parsed instanceof Map<?, ?> m ? m.get("hash") : null;
                if (h instanceof String s && !s.isBlank()) lastHash = s;
            }
            if (lastHash != null) {
                prevHash = lastHash;
                if (headHash != null && !headHash.equals(lastHash)) {
                    LoggerFactory.getLogger(AuditService.class).warn(
                            "audit chain head {} != last line {}, continue from last line",
                            headHash, lastHash);
                }
            } else if (headHash != null) {
                prevHash = headHash;
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(AuditService.class)
                    .warn("audit chain init failed, start from GENESIS: {}", e.toString());
        }
    }

    private void writeEvent(Map<String, Object> ev) {
        try {
            // H2 + OP-A7：请求级关联 id 来自 MDC（同步面=filter 注入；编排面=虚拟线程任务内重挂）。
            // 两个 id **并存且互不覆盖**（ADR-0007 修订注）：
            //   request_id = 服务端自生的单次请求 id（服务端权威，调用方输入顶不掉）；
            //   trace_id   = 调用方提供的调用链 id（agent 分多步调用时用它整段取出）。
            // 缺省（系统内部触发的审计、调用方没给 trace）不落字段——不编造身份。
            String rid = org.slf4j.MDC.get(com.opspilot.metrics.RequestIdFilter.KEY);
            if (rid != null) ev.put("request_id", rid);
            String trace = org.slf4j.MDC.get(com.opspilot.metrics.RequestIdFilter.TRACE_KEY);
            if (trace != null) ev.put("trace_id", trace);
            synchronized (this) {
                ev.put("seq", ++seq);
                ev.put("prev_hash", prevHash);
                String prefix = mapper.writeValueAsString(ev);
                String hash = sha256(prevHash + "\n" + prefix);
                ev.put("hash", hash);
                prevHash = hash;
                ring.addLast(ev);
                while (ring.size() > RING_CAP) ring.removeFirst();
                AUDIT.info(mapper.writeValueAsString(ev));
                // 链头单独落盘：轮转后新文件仍能接续；写入失败只降级为"重启后需从最后一行恢复"，
                // 不影响审计行本身（审计失败计数只由外层 catch 负责）。
                if (persistChain) {
                    try {
                        Files.createDirectories(chainHead.getParent());
                        Files.writeString(chainHead, hash, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                        // 不阻断、不计失败：链头是可重建的派生状态，审计行才是事实。
                    }
                }
            }
        } catch (Exception e) {
            // 审计失败不阻断业务链路，但必须留痕可排障；同时进 aiops_audit_write_failures_total
            // （G0：静默丢合规证据是"业务越正常越没人发现"的故障，必须能告警）。
            AuditWriteFailures.markFailure();
            LoggerFactory.getLogger(AuditService.class).warn("audit log failed: {}", e.toString());
        }
    }

    private static String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String lastLine(Path file) {
        String last = null;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) last = line;
            }
        } catch (Exception ignored) {
            return null;
        }
        return last;
    }
}
