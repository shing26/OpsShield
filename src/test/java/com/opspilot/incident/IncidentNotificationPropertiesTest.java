package com.opspilot.incident;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置 fail-fast（契约 AC3）：旧配置映射、新旧冲突、URL 安全、format/优先级。
 * 这些错误必须在启动期暴露；运行到第一次告警才发现"发不出去"是不可接受的。
 */
class IncidentNotificationPropertiesTest {

    private static IncidentNotificationProperties props(
            String legacyUrl, Map<String, IncidentNotificationProperties.Channel> channels,
            String allowedHosts) {
        return new IncidentNotificationProperties(legacyUrl, "generic", 256, 3000,
                "22:00-08:00", "Asia/Shanghai", 90, "0 17 4 * * *",
                channels, "P2", "", allowedHosts, 8, 1000, 300000, 5000, 20, 1000);
    }

    private static IncidentNotificationProperties.Channel channel(String url) {
        return new IncidentNotificationProperties.Channel(
                "generic", url, "", "P3", List.of(), "", true, 5);
    }

    @Test
    void legacyWebhookMapsToDefaultChannel() {
        IncidentNotificationProperties p = props("https://example.test/hook", Map.of(), "");
        assertEquals(java.util.Set.of("default"), p.effectiveChannels().keySet());
        assertEquals("https://example.test/hook", p.effectiveChannels().get("default").url());
        assertTrue(p.webhookEnabled());
    }

    @Test
    void legacyAndChannelsTogetherFailFast() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> props("https://example.test/hook",
                        Map.of("alpha", channel("https://example.test/a")), ""));
        assertTrue(e.getMessage().contains("不能同时配置"));
    }

    @Test
    void nonLoopbackPlainHttpIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> props("", Map.of("alpha", channel("http://example.test/hook")), ""));
        assertTrue(e.getMessage().contains("https"));
        // loopback http 是允许的（本机验收 stub）。
        assertDoesNotThrow(() -> props("", Map.of("alpha", channel("http://127.0.0.1:9/hook")), ""));
        assertDoesNotThrow(() -> props("", Map.of("alpha", channel("http://127.0.0.2:9/hook")), ""));
        assertDoesNotThrow(() -> props("", Map.of("alpha", channel("http://[::1]:9/hook")), ""));
        assertDoesNotThrow(() -> props("", Map.of("alpha", channel("http://localhost:9/hook")), ""));
        // 不能把 127. 前缀当回环：这两个都是公网可解析域名，必须拒绝明文 HTTP。
        assertThrows(IllegalArgumentException.class,
                () -> props("", Map.of("alpha", channel("http://127.example.com/hook")), ""));
        assertThrows(IllegalArgumentException.class,
                () -> props("", Map.of("alpha", channel("http://127.0.0.1.evil.com/hook")), ""));
        assertFalse(IncidentNotificationProperties.isLoopbackHost("127.example.com"));
        assertFalse(IncidentNotificationProperties.isLoopbackHost("127.0.0.1.evil.com"));
        assertTrue(IncidentNotificationProperties.isLoopbackHost("127.0.0.1"));
        assertTrue(IncidentNotificationProperties.isLoopbackHost("[::1]"));
    }

    @Test
    void userinfoAndFragmentAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> props("", Map.of("alpha", channel("https://u:p@example.test/hook")), ""));
        assertThrows(IllegalArgumentException.class,
                () -> props("", Map.of("alpha", channel("https://example.test/hook#frag")), ""));
    }

    @Test
    void unknownFormatAndPriorityFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new IncidentNotificationProperties.Channel(
                        "slack", "https://example.test/hook", "", "P3", List.of(), "", true, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new IncidentNotificationProperties.Channel(
                        "generic", "https://example.test/hook", "", "P9", List.of(), "", true, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new IncidentNotificationProperties("", "generic", 256, 3000,
                        "22:00-08:00", "Asia/Shanghai", 90, "0 17 4 * * *",
                        Map.of(), "P9", "", "", 8, 1000, 300000, 5000, 20, 1000));
    }

    @Test
    void allowedHostsMustCoverEveryChannel() {
        assertDoesNotThrow(() -> props("",
                Map.of("alpha", channel("https://alerts.example.test/hook")),
                "alerts.example.test,other.test"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> props("", Map.of("alpha", channel("https://alerts.example.test/hook")),
                        "other.test"));
        assertTrue(e.getMessage().contains("allowed-hosts"));
    }

    @Test
    void quietWindowCanBeOverriddenPerChannel() {
        IncidentNotificationProperties.Channel c = new IncidentNotificationProperties.Channel(
                "generic", "https://example.test/hook", "", "P3", List.of(), "01:00-02:00", true, 5);
        assertEquals("01:00-02:00", c.quietHours());
    }
}
