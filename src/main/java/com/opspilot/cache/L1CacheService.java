package com.opspilot.cache;

import java.time.Duration;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.KvStore;

/**
 * L1 精确缓存：key = tenant + authLevel + 指纹，TTL 2h。
 * 掺 authLevel 防止不同密级用户串答案（权限隔离延伸）。
 *
 * <p><b>2026-10-17 起键吃的是指纹，不再自己哈希 query 文本</b>：Alertmanager 的规则模板会把
 * {@code {{ $value }}} 渲染进注解，同一条告警每次重发的文案都带一个新数字，而旧的
 * {@code SHA-256(normalize(query))} 让每次都成为新键、L1 永远 miss
 * （实证见 {@code docs/qa/2026-10-17-alertmanager-intake.md} §3.1）。
 * 归一化/身份的职责收在 {@link com.opspilot.storm.FingerprintService} 一处：带 {@code alertId}
 * 走不透明的告警身份，不带才回退到文本指纹。L1 只消费指纹，不再解释 query。
 */
@Service
public class L1CacheService {

    private final KvStore store;
    private final OpsPilotProperties props;

    public L1CacheService(KvStore store, OpsPilotProperties props) {
        this.store = store;
        this.props = props;
    }

    public String key(String tenantId, int authLevel, String fingerprint) {
        // 纵深防御（P1）：tenant 校验主闸门在 JwtAuthFilter（缺失/空/超长→401），
        // 这里 fail-fast 兜底，确保 null 租户永远不会拼进缓存 key 造成跨租户串答案。
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("L1 缓存 key 拒绝空租户（上游鉴权应已 401）");
        }
        return "cache:l1:" + tenantId + ":" + authLevel + ":" + fingerprint;
    }

    public String get(String tenantId, int authLevel, String fingerprint) {
        return store.getString(key(tenantId, authLevel, fingerprint));
    }

    public void put(String tenantId, int authLevel, String fingerprint, String answerJson) {
        store.setString(key(tenantId, authLevel, fingerprint), answerJson,
                Duration.ofHours(props.cache().l1TtlHours()));
    }

    /** 清空全部 L1 缓存（验收隔离 / 演示重置）。 */
    public long flush() {
        return store.deleteByPrefix("cache:l1:");
    }
}
