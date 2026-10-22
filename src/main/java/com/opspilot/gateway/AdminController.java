package com.opspilot.gateway;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.cache.L1CacheService;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.BackupStatusService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.resilience.DegradationState;
import com.opspilot.resilience.DegradationState.Level;
import com.opspilot.resilience.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运维端点：**全部要求平台管理员** —— DB `role=="platform"` ∧ `auth_level>=3`。
 * 2026-09-11 QA P0-2/P1-1：旧口径"level≥3 即管理员"把密级当信任，外来租户 L3 可重建共享索引/
 * 清全局缓存（已被实测误触发）；metrics 曾完全无门禁暴露全局计数与内部模型名。
 * 索引/集合/缓存/H2 都是平台级共享资源，任何单一租户的用户（无论多高密级）不得持有其写权。
 * role 与 tenant/level 同源于 DB（JwtAuthFilter 每请求注入），保持"DB 是唯一真相"（ADR-0008）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Set<String> VALID_LEVELS = Set.of("L0", "L1", "L2", "auto");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminController.class);

    private final OpsMetrics metrics;
    private final DegradationState degrade;
    private final L1CacheService l1;
    private final L2SemanticCacheService l2;
    private final OpsPilotProperties props;
    private final com.opspilot.ingest.IngestionRunner ingestion;
    private final com.opspilot.auth.UserStore users;
    private final com.opspilot.health.HealthProbe healthProbe;
    private final com.opspilot.metrics.AuditService audit;
    private final com.opspilot.storm.SingleFlight singleFlight;
    private final com.opspilot.resilience.QuotaService quota;
    private final BackupStatusService backupStatus;   // 最近成功备份的可观测面（G0）
    private final org.springframework.boot.info.BuildProperties build; // 面板 build 指纹（build-info 缺省时为 null）

    public AdminController(OpsMetrics metrics, DegradationState degrade,
                           L1CacheService l1, L2SemanticCacheService l2, OpsPilotProperties props,
                           com.opspilot.ingest.IngestionRunner ingestion, com.opspilot.auth.UserStore users,
                           com.opspilot.health.HealthProbe healthProbe, com.opspilot.metrics.AuditService audit,
                           com.opspilot.storm.SingleFlight singleFlight,
                           com.opspilot.resilience.QuotaService quota,
                           BackupStatusService backupStatus,
                           @org.springframework.lang.Nullable org.springframework.boot.info.BuildProperties build) {
        this.metrics = metrics;
        this.degrade = degrade;
        this.l1 = l1;
        this.l2 = l2;
        this.props = props;
        this.ingestion = ingestion;
        this.users = users;
        this.healthProbe = healthProbe;
        this.audit = audit;
        this.singleFlight = singleFlight;
        this.quota = quota;
        this.backupStatus = backupStatus;
        this.build = build;
    }

    /** 平台门禁 + 拒绝留痕（QA P1-2：破坏性端点被拒/成功都必须可回溯）。 */
    private void requirePlatformAdmin(HttpServletRequest http, String action) {
        UserContext u = JwtAuthFilter.from(http);
        if (u == null || !("platform".equals(u.role()) && u.authLevel() >= 3)) {
            audit.logAdmin(u, action, "refused", null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要平台管理员凭证（role=platform 且 auth_level>=3）");
        }
    }

    /** 清空 L1+L2 缓存（验收隔离 / 演示重置）。 */
    @PostMapping("/cache/flush")
    public Map<String, Object> flushCache(HttpServletRequest http) {
        requirePlatformAdmin(http, "cache/flush");
        long n = l1.flush();
        l2.flush();
        audit.logAdmin(JwtAuthFilter.from(http), "cache/flush", "ok", "l1_flushed=" + n);
        return Map.of("l1_flushed", n);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(HttpServletRequest http) {
        requirePlatformAdmin(http, "metrics");
        return metricsView();
    }

    /** 部署级健康明细（平台管理员）：依赖探测+live 口径+索引规模；组件聚合另见 /actuator/health。 */
    @GetMapping("/health")
    public Map<String, Object> health(HttpServletRequest http) {
        requirePlatformAdmin(http, "health");
        return healthProbe.summary();
    }

    /** body: {"level":"L0|L1|L2"} 锁定；{"level":"auto"} 解除手动锁定。 */
    @PostMapping("/degrade")
    public Map<String, Object> degrade(@RequestBody Map<String, String> body, HttpServletRequest http) {
        requirePlatformAdmin(http, "degrade");
        String level = body.getOrDefault("level", "auto");
        if (!VALID_LEVELS.contains(level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "非法 level，允许值: " + VALID_LEVELS);
        }
        if ("auto".equalsIgnoreCase(level)) {
            degrade.manualClear();
        } else {
            degrade.manualSet(Level.valueOf(level.toUpperCase()));
        }
        audit.logAdmin(JwtAuthFilter.from(http), "degrade", "ok", "level=" + level);
        return Map.of("degradation_level", degrade.current().name(), "manual", degrade.isManual());
    }

    /** P4：异步触发 blue/green 重灌（平台管理员）；已有任务在跑返回 409。 */
    @PostMapping("/reingest")
    public Map<String, Object> reingest(HttpServletRequest http) {
        requirePlatformAdmin(http, "reingest");
        if (!ingestion.reingestAsync()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有 reingest 在执行中");
        }
        audit.logAdmin(JwtAuthFilter.from(http), "reingest", "ok", null);
        return Map.of("accepted", true, "hint", "完成后 /metrics 的 reingest_last 会更新");
    }

    /** 运维备份（平台管理员）：网关是 H2 的所有者，BACKUP TO 在自己的连接上执行——
     *  不依赖 AUTO_SERVER TCP（Windows 防火墙常拦），gateway 活着就能备，cron 友好。
     *  路径非法是**客户端错误**（QA P2-2：曾以 500 呈现，污染 5xx SLO 且用户看不懂），
     *  H2BackupPath 白名单拒绝翻译为 400 + 可操作文案。 */
    @PostMapping("/backup")
    public Map<String, Object> backup(@RequestBody Map<String, String> body, HttpServletRequest http) {
        requirePlatformAdmin(http, "backup");
        String to;
        try {
            to = users.backup(body.getOrDefault("to", null));
        } catch (IllegalArgumentException e) {
            audit.logAdmin(JwtAuthFilter.from(http), "backup", "rejected", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "备份路径非法：须为 backup/ 目录下的 .zip，且不含引号/分号/反斜杠");
        }
        // G0：响应必须能自证"这份产物是哪个字节、能否解开"——旧响应只有路径，
        // 调用方无法确认容器卷映射/截断等问题（QA P1-3 的同类假绿）。
        // 算不出摘要不是错误（容器形态宿主可能看不到刚写的文件），但 verified=false 要如实说。
        java.nio.file.Path artifact = java.nio.file.Path.of(to);
        String sha = "";
        boolean verified = false;
        try {
            sha = BackupStatusService.sha256(artifact);
            verified = BackupStatusService.zipIntact(artifact);
        } catch (Exception e) {
            log.warn("备份产物核验失败（响应给 verified=false）: {} ({})", to, e.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backup", to);
        out.put("sha256", sha);
        out.put("verified", verified);
        audit.logAdmin(JwtAuthFilter.from(http), "backup", "ok",
                sha.isEmpty() ? to : to + " sha256=" + sha);
        return out;
    }

    /**
     * Ops Console 唯一状态快照（ADR-0009，只读）：build 指纹 + metrics + health + 运行态。
     * 纪律同 metrics/health——成功不落审计（面板 2s 轮询不得污染证据流），被拒由
     * requirePlatformAdmin 留痕；全部数字来自真实计数器，面板侧速率=相邻两次真值求差。
     */
    @GetMapping("/state")
    public Map<String, Object> state(HttpServletRequest http) {
        requirePlatformAdmin(http, "state");
        UserContext u = JwtAuthFilter.from(http);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", System.currentTimeMillis());

        Map<String, Object> b = new LinkedHashMap<>();
        var rt = java.lang.management.ManagementFactory.getRuntimeMXBean();
        b.put("version", build == null ? "dev" : build.getVersion());
        b.put("time", build == null ? "" : String.valueOf(build.getTime()));
        b.put("jvm", System.getProperty("java.version"));
        b.put("uptime_s", rt.getUptime() / 1000);
        b.put("pid", ProcessHandle.current().pid());
        b.put("commit", buildCommit(System.getenv()));   // G1-W1.4：面板要能回答"现在跑的是哪一版"
        out.put("build", b);

        out.put("metrics", metricsView());
        out.put("health", healthProbe.summary());

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("inflight", degrade.inflightValue());
        runtime.put("sf_groups", singleFlight.inFlightGroups());
        runtime.put("sf_keys_top", singleFlight.peekKeys(8));
        // 两个阈值 = 两个**不同**的触发条件，键名必须各自说清：曾只暴露一个笼统的 "threshold"，
        // 外部脚本据此把它误标成 inflight 阈值——口径含糊会直接变成错误结论，故改名并双双暴露。
        //   在途数达 inflight_threshold → L1（高负载）；LLM 连续失败达 llm_failure_threshold → L2（熔断）。
        runtime.put("degradation", Map.of(
                "level", degrade.current().name(),
                "manual", degrade.isManual(),
                "failures", degrade.llmConsecutiveFailures(),
                "llm_failure_threshold",
                props.degrade() == null ? 3 : props.degrade().llmFailureThreshold(),
                "inflight_threshold",
                props.degrade() == null ? 40 : props.degrade().inflightThreshold(),
                "cooldown_s", degrade.l2CooldownRemainingSeconds()));
        runtime.put("quota", Map.of(
                "sub", u.sub(), "used", quota.usedToday(u.sub()), "limit", quota.dailyLimit()));
        runtime.put("reingest", Map.of("busy", ingestion.busy(),
                "last", java.util.Objects.toString(ingestion.lastResult(), "unknown")));
        // 备份可信度（G0）：与 gauge aiops.backup.last_success.timestamp.seconds 同源同口径，
        // 面板显示的"备份龄"与告警规则算出来的必须是同一个数。
        runtime.put("backup", backupView());
        out.put("runtime", runtime);
        return out;
    }

    /** 审计事件增量拉取（游标=全局 seq；truncated 显式告知轮转/重启丢失，禁静默空洞）。 */
    @GetMapping("/audit/recent")
    public Map<String, Object> auditRecent(@RequestParam(defaultValue = "0") long since,
                                           @RequestParam(defaultValue = "50") int limit,
                                           HttpServletRequest http) {
        requirePlatformAdmin(http, "audit/recent");
        var r = audit.recentSince(since, limit);
        return Map.of("events", r.events(), "max_seq", r.maxSeq(), "truncated", r.truncated());
    }

    /**
     * 部署 commit（G1-W1.4）：{@code OPSPILOT_BUILD_COMMIT} 优先，其次 {@code GIT_COMMIT}，
     * 都没有回 {@code unknown}。
     *
     * <p>为什么读环境而不是 {@code BuildProperties}：build-info 只产 version/time（pom 里就一个
     * build-info goal，没引 git-commit-id 插件），而环境变量是容器与宿主部署都已存在的注入通道
     * （CI 的 GIT_COMMIT、compose 的 environment 都能给），不新增依赖。
     *
     * <p>为什么缺省是 {@code unknown} 而不是空串：面板必须能区分"没注入"（unknown）与
     * "注入了空值"（配置错误）。显式键优先：两个都设时，OPSPILOT_BUILD_COMMIT 是更具体的那个。
     * 截断到 64 字符——build 指纹是给人看的短标识，不该让一个畸形环境变量把 /state 撑大。
     */
    static String buildCommit(Map<String, String> env) {
        for (String key : new String[]{"OPSPILOT_BUILD_COMMIT", "GIT_COMMIT"}) {
            String v = env == null ? null : env.get(key);
            if (v != null && !v.isBlank()) {
                String trimmed = v.trim();
                return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
            }
        }
        return "unknown";
    }

    /** 备份状态子块：last_success（epoch 秒，0=无记录）/ age_hours（-1=无记录）/ verified。 */
    private Map<String, Object> backupView() {
        var s = backupStatus.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("last_success", s.lastSuccessEpoch());
        m.put("age_hours", s.ageHours() < 0 ? -1.0 : Math.round(s.ageHours() * 100.0) / 100.0);
        m.put("verified", s.verified());
        return m;
    }

    /** metrics() 端点与 /state 共用的装配（单一键源，面板契约两处一致）。 */
    private Map<String, Object> metricsView() {
        Map<String, Object> m = new LinkedHashMap<>(metrics.snapshot());
        var ds = props.dashscope();
        m.put("backend", Map.of(
                "embedding", ds.live() ? "dashscope:" + ds.embeddingModel() : "mock-lexical-hash",
                "rerank", ds.live() ? "dashscope:" + ds.rerankModel() : "mock-idf-coverage",
                "llm", ds.live() ? "dashscope:" + ds.llmModel() : "mock-template"));
        m.put("degradation_level", degrade.current().name());
        m.put("degradation_manual", degrade.isManual());
        m.put("inflight", degrade.inflightValue());
        m.put("reingest_busy", ingestion.busy());
        m.put("reingest_last", ingestion.lastResult());
        return m;
    }
}
