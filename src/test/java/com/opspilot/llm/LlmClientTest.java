package com.opspilot.llm;

import com.opspilot.common.ClientAbortedException;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.metrics.OpsMetrics;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * H3 重试语义锁：仅"首 token 吐出前"的瞬时错（429/网络层/HTTP 5xx）允许单次退避重试；
 * 已吐 token 后重试会造成答案重复，禁止；429 与网络错分类计数，终态口径不变。
 * P1（2026-09-19）：5xx/4xx 的瞬时性判定走 LlmHttpException.status 字段——文案不再承重，
 * 另有反向锁证明"文案像 5xx 的普通异常"不再被误判为瞬时。
 * attemptOnce 以 spy 桩掉——不真发 HTTP。
 */
class LlmClientTest {

    private OpsMetrics metrics;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        OpsPilotProperties props = new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://x", "k", "e", 1024, "r", "q", 30, 3000, 15000, "live"),
                null, null, null, null, null, null, null);
        metrics = mock(OpsMetrics.class);
        client = new LlmClient(props, metrics);
        client.backoff429Ms = 1;
        client.backoffNetworkMs = 1;
    }

    private static RuntimeException networkErr() {
        return new RuntimeException("LLM 调用失败: reset", new java.io.IOException("reset"));
    }

    @Test
    void transientFailureBeforeFirstTokenRetriesOnce() {
        var spy = spy(client);
        doThrow(networkErr()).doReturn("ok").when(spy).attemptOnce(any(), any());
        assertEquals("ok", spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics).llmRetry();
        verify(metrics, never()).llmNetworkError();
    }

    @Test
    void doubleTransientFailureCountsRetryAndNetworkError() {
        var spy = spy(client);
        doThrow(networkErr()).when(spy).attemptOnce(any(), any());
        assertThrows(RuntimeException.class,
                () -> spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics).llmRetry();
        verify(metrics).llmNetworkError();
    }

    @Test
    void rateLimit429RetriesOnceWithTypedPropagation() {
        var spy = spy(client);
        doThrow(new LlmClient.LlmRateLimitedException())
                .doReturn("ok").when(spy).attemptOnce(any(), any());
        assertEquals("ok", spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics).llmRetry();
        // 429 不占网络错分类（两口径独立）
        verify(metrics, never()).llmNetworkError();
        verify(metrics, never()).llmRateLimited(); // 终态 429 计数归编排层
    }

    /** 首 token 已吐后再失败：重试=答案重复，必须直抛（网络错仍计数）。 */
    @Test
    void failureAfterFirstTokenNeverRetries() {
        var spy = spy(client);
        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(1);
            onToken.accept("half");
            throw networkErr();
        }).when(spy).attemptOnce(any(), any());
        assertThrows(RuntimeException.class,
                () -> spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics, never()).llmRetry();
        verify(metrics).llmNetworkError();
    }

    /** HTTP 5xx = 瞬时（P1 后按 status 字段判定，不再读文案）：重试耗尽计网络错，类型化异常原样上抛。 */
    @Test
    void http5xxRetriesOnceAndCountsNetworkError() {
        var spy = spy(client);
        doThrow(new LlmClient.LlmHttpException(503)).when(spy).attemptOnce(any(), any());
        var thrown = assertThrows(LlmClient.LlmHttpException.class,
                () -> spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        assertEquals(503, thrown.status());
        verify(metrics).llmRetry();
        verify(metrics).llmNetworkError();
    }

    /** P1 反向锁：message 文案不再承重——文案里带 "LLM HTTP 503" 的普通异常不是瞬时错。 */
    @Test
    void messageTextIsNoLongerLoadBearingForClassification() {
        var spy = spy(client);
        doThrow(new RuntimeException("LLM HTTP 503（普通异常，非类型化）"))
                .when(spy).attemptOnce(any(), any());
        assertThrows(RuntimeException.class,
                () -> spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics, never()).llmRetry();
        verify(metrics, never()).llmNetworkError();
    }

    /**
     * G1：客户端断开必须原样穿透，不能落入 LLM 失败分类——否则一次"用户关页面"会被记成
     * 网络错并推进全局熔断，把取消误判成上游故障。也不允许重试（连接已经没人接收）。
     */
    @Test
    void clientAbortPropagatesWithoutRetryOrNetworkClassification() {
        var spy = spy(client);
        doThrow(new ClientAbortedException("client disconnected")).when(spy).attemptOnce(any(), any());
        assertThrows(ClientAbortedException.class,
                () -> spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics, never()).llmRetry();
        verify(metrics, never()).llmNetworkError();
    }

    /** 非 5xx（4xx 配置类）不重试——重试只会烧额度不改变结局（P1 后走类型化异常）。 */
    @Test
    void nonTransientFailureNeverRetries() {
        var spy = spy(client);
        doThrow(new LlmClient.LlmHttpException(401)).when(spy).attemptOnce(any(), any());
        assertThrows(RuntimeException.class,
                () -> spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics, never()).llmRetry();
        verify(metrics, never()).llmNetworkError();
    }
}
