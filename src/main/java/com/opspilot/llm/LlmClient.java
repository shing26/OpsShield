package com.opspilot.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.opspilot.common.ClientAbortedException;
import com.opspilot.metrics.OpsMetrics;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import com.opspilot.config.OpsPilotProperties;

/**
 * qwen-plus 流式对话：JDK HttpClient 手写解析 OpenAI 兼容 SSE（无 SDK，DoD 要求无重量级黑盒依赖）。
 * 429 抛 LlmRateLimitedException 供降级状态机熔断；其余非 200 抛 LlmHttpException（带
 * status 字段，瞬时性判定读字段不读文案——P1，2026-09-19 外部评审核实采纳）。
 *
 * H3（生产就绪度 2026-09-12）：**单次退避重试**——仅当失败发生在**首 token 吐出之前**
 * （重试不会造成答案重复）且错误为瞬时类（429 / 网络层 IOException / HTTP 5xx）。
 * 已吐 token 后失败、或非瞬时错（4xx 配置类）一律直接上抛。429 与网络错分开计数
 * （llm_retries / llm_network_errors），终态 429 仍计 llm_rate_limited 供熔断。
 */
@Component
public class LlmClient {

    public static class LlmRateLimitedException extends RuntimeException {
        public LlmRateLimitedException() { super("LLM 429 rate limited"); }
    }

    /**
     * 上游非 200 且非 429：带 status 的类型化异常。瞬时性判定只读 status 字段、不读 message——
     * 此前按 "LLM HTTP 5" 文案前缀判 5xx，措辞一改分类就静默失准且无测试可抓
     * （2026-09-19 外部评审核实采纳，P1）。message 保留同文案仅供人读。
     */
    public static class LlmHttpException extends RuntimeException {
        private final int status;

        public LlmHttpException(int status) {
            super("LLM HTTP " + status);
            this.status = status;
        }

        public int status() { return status; }

        /** 瞬时性判定收口在此：5xx 可退避重试，4xx 配置类不重试。 */
        public boolean isServerSide() { return status >= 500; }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpsPilotProperties props;
    private final OpsMetrics metrics;
    // 退避时长抽字段：测试可压到 1ms（真实 429=1s / 网络错=400ms）
    volatile long backoff429Ms = 1_000L;
    volatile long backoffNetworkMs = 400L;

    public LlmClient(OpsPilotProperties props, OpsMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    /** 流式生成：onToken 逐 token 回调，返回完整答案文本。 */
    public String streamChat(List<Map<String, String>> messages, Consumer<String> onToken) {
        if (!props.dashscope().live()) {
            return mockStream(messages, onToken);
        }
        AtomicBoolean tokenEmitted = new AtomicBoolean(false);
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            AtomicBoolean touched = new AtomicBoolean(false);
            try {
                return attemptOnce(messages, t -> {
                    touched.set(true);
                    tokenEmitted.set(true);
                    onToken.accept(t);
                });
            } catch (Exception e) {
                RuntimeException err = asRuntime(e);
                if (touched.get() || attempt == 1 || !isTransient(e)) {
                    // 终态网络错无论是否已吐 token 都计数（重试耗尽或不可重试的失败）
                    if (isNetworkError(e)) metrics.llmNetworkError();
                    throw err;
                }
                metrics.llmRetry();
                sleepBackoff(e);
            }
        }
        throw last == null ? new IllegalStateException("unreachable retry loop") : last;
    }

    /** 单次尝试（建立连接 → 状态判定 → 流读取）。异常分类交给调用方的重试判定。
     *  包级可见：retry 语义单测以 Mockito spy 桩本方法（不真发 HTTP）。 */
    String attemptOnce(List<Map<String, String>> messages, Consumer<String> onToken) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", props.dashscope().llmModel(),
                    "messages", messages,
                    "stream", true,
                    "temperature", 0.2));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.dashscope().baseUrl() + "/compatible-mode/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(props.dashscope().llmTimeoutSeconds()))
                    .header("Authorization", "Bearer " + props.dashscope().apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            if (status != 200) {
                closeQuietly(resp.body());   // 错误分支不排空/关闭 body 会泄漏连接
                if (status == 429) throw new LlmRateLimitedException();
                throw new LlmHttpException(status);
            }

            StringBuilder full = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.equals("[DONE]")) break;
                    JsonNode node = mapper.readTree(payload);
                    JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String token = delta.asText();
                        if (!token.isEmpty()) {
                            full.append(token);
                            onToken.accept(token);
                        }
                    }
                }
            }
            return full.toString();
        } catch (LlmRateLimitedException e) {
            throw e;
        } catch (LlmHttpException e) {
            throw e;   // 类型化异常直传：一旦被包裹就丢 status 字段，分类会退化回读文案
        } catch (ClientAbortedException e) {
            // 客户端取消是控制流，不是上游失败：必须原样穿透，否则 ChatOrchestrator 会把它
            // 当成 LLM 故障（开熔断/记网络错），"用户走了"被误判成"模型挂了"。
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /** 瞬时类：429（退避后上游可能放行）、网络层 IOException/超时、HTTP 5xx（按 status 字段判）。4xx 配置错不在内。 */
    private static boolean isTransient(Exception e) {
        if (e instanceof LlmRateLimitedException) return true;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof java.io.IOException || c instanceof java.net.http.HttpTimeoutException) return true;
            if (c instanceof LlmHttpException h && h.isServerSide()) return true;
        }
        return false;
    }

    private static boolean isNetworkError(Exception e) {
        return !(e instanceof LlmRateLimitedException) && isTransient(e);
    }

    private void sleepBackoff(Exception e) {
        try {
            Thread.sleep(e instanceof LlmRateLimitedException ? backoff429Ms : backoffNetworkMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException asRuntime(Exception e) {
        return e instanceof RuntimeException re ? re : new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (java.io.IOException ignored) {
            // 关闭失败不影响错误传播
        }
    }

    /** Mock 流式：从 user 消息抽取参考上下文 → 组装答案 → 按 24 字符切片回调。 */
    private String mockStream(List<Map<String, String>> messages, Consumer<String> onToken) {
        String user = messages.get(messages.size() - 1).get("content");
        List<String> chunks = new java.util.ArrayList<>();
        int idx = user.indexOf("【参考上下文】");
        if (idx >= 0) {
            String ctx = user.substring(idx);
            // 按 [参考N] 标记切块：split 首段为「【参考上下文】」前言，须丢弃
            String[] parts = ctx.split("\\[参考\\d+\\] ");
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isBlank()) chunks.add(parts[i].trim());
            }
        }
        String answer = MockEngine.answer(chunks);
        for (int i = 0; i < answer.length(); i += 24) {
            String piece = answer.substring(i, Math.min(i + 24, answer.length()));
            onToken.accept(piece);
        }
        return answer;
    }
}
