package com.opspilot.metrics;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求级关联标识（H2，生产就绪度 2026-09-12）：8 位短 id 注入 MDC，
 * 让 app.log 的每行日志（logback %X{request_id}）与 audit.jsonl 的审计行
 * （AuditService.writeEvent 读取 MDC）能用同一 id 关联——"审计发现异常→回查生成链"不再断链。
 * 编排异步段（虚拟线程任务）由 ChatOrchestrator.submit 显式携带——MDC 是 thread-local，
 * 不跨线程传播，必须在任务内重挂。
 * 命名注记：不能叫 RequestContextFilter——与 Boot WebMvcAutoConfiguration 内置同名 bean 冲突
 * （BeanDefinitionOverrideException，实测启动失败）。
 *
 * <p><b>调用方关联键 {@code X-Trace-Id}（2026-09-28，OP-A7）</b>：上面那个 {@code request_id} 是
 * **服务端自生**的，调用方拿不到——于是"被测 agent 分三步调用本底座"时，三行审计之间没有任何
 * 东西能把它们串成**一条调用链**（既有 {@code fingerprint} 是**内容派生**的"问题身份"，三步问不同
 * 错误码就会散成三个指纹）。故新增一个**调用方提供**的键，随请求头进入 MDC 并落到审计行。
 * 两者分工**互不覆盖**（ADR-0007 修订注）：{@code trace_id}=调用链（跨调用整段取出）；
 * {@code request_id}=单次请求回查（服务端权威，绝不因调用方输入而变）。
 *
 * <p><b>该列有两个生产者（2026-10-17，ADR-0019）</b>：其一仍是上层 agent 自述的调用链 id；
 * 其二是告警桥从 Alertmanager 分组派生的 {@code am-…}（一次事故的关联身份，见 CONTEXT.md
 * 「告警分组身份」）。两者共用同一列是**刻意的**——列语义是"调用方给的关联键"，不是"某种特定
 * 调用方的键"。术语上的区分（调用链 / 事故）属领域模型，见 CONTEXT.md。
 *
 * <p>为什么要放在过滤器而不是请求体字段：本过滤器作用于**全部 servlet 路径**，一处改动即覆盖
 * copilot / v1 / search / feedback 四个面，且不碰任何 DTO——{@code /v1} 的 OpenAI 兼容请求体
 * 保持纯净（往那个 record 加字段才是真的动了协议面）。
 *
 * <p>校验口径与 fingerprint/mode/source 同范式：8–64 位 {@code [A-Za-z0-9_-]}，**非法即 400 + 留痕**
 * （不静默降级成"没有 trace"——那会让调用方以为自己接上了）。空白值按**未提供**处理：不少 HTTP
 * 客户端会默认带空头，把那当非法是给自己找 400。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)   // 先于 JwtAuthFilter（@Order(0)），守卫拒绝日志同样带 id
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String KEY = "request_id";
    public static final String TRACE_KEY = "trace_id";
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** 词法收口：只允许 URL-safe 短串，防注入/超长/控制字符进日志（长度上限同时护住审计行体积）。 */
    private static final Pattern TRACE_VALUE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final AuditService audit;

    public RequestIdFilter(AuditService audit) {
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(KEY, UUID.randomUUID().toString().substring(0, 8));
        try {
            String raw = req.getHeader(TRACE_HEADER);
            if (raw != null && !raw.isBlank()) {
                String trace = raw.trim();
                if (!TRACE_VALUE.matcher(trace).matches()) {
                    reject(req, resp, trace);
                    return;
                }
                MDC.put(TRACE_KEY, trace);
            }
            chain.doFilter(req, resp);
        } finally {
            MDC.remove(KEY);       // Tomcat 线程池复用，必须清理防串号
            MDC.remove(TRACE_KEY);
        }
    }

    /**
     * 非法 trace 头 → 400。形状必须自己给：filter 短路早于 {@code @RestControllerAdvice}，
     * 故沿用 GlobalExceptionHandler 的两面形状（/v1 = OpenAI error 信封，其余 = code/message），
     * 免得同族错误在两条出口上长两个样（第五轮 QA "错误分层" 的既有成果）。
     */
    private void reject(HttpServletRequest req, HttpServletResponse resp, String raw) throws IOException {
        String msg = "X-Trace-Id 非法（须 8–64 位 [A-Za-z0-9_-] / malformed X-Trace-Id header）";
        audit.logInvalid(null, req.getRequestURI(), msg);   // 此刻尚未鉴权：sub 留空，不编造身份
        // 形状取单点定义（ErrorBodies）：filter 短路早于 @RestControllerAdvice，形状必须自己给，
        // 但"自己给"不等于"各写一份"——那正是本轮收敛掉四种错误形状的根因。
        if (req.getRequestURI().startsWith("/v1")) {
            com.opspilot.gateway.ErrorBodies.writeOpenAi(resp, 400, msg, "invalid_request_error", null);
            return;
        }
        com.opspilot.gateway.ErrorBodies.writeApi(resp, 400, "INVALID_REQUEST", msg);
    }
}
