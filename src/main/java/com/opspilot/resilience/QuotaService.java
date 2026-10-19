package com.opspilot.resilience;

import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import com.opspilot.store.KvStore;

/**
 * 每用户日配额（P4）：Redis INCR + 首次设 TTL，超限 429。
 * 目的不是防攻击而是成本护栏——为异常脚本/死循环请求兜底，给 LLM 预算设硬上限。
 * 默认 5000/天：50 人团队 ×100 次/人；同时必须容得下验收风暴场景（A2-5 的 500 并发同 sub，
 * 且它们被 Single-Flight 收敛后根本不烧 LLM——护栏防的是失控循环，不是去重后的并发）。
 * 仅挂 /chat/stream；/search 是评测/审计路径不限流。登录与管理端点豁免。
 */
@Component
public class QuotaService {

    private final KvStore store;
    private final long dailyLimit;

    public QuotaService(KvStore store,
                        @Value("${opspilot.quota.daily-limit:5000}") long dailyLimit) {
        this.store = store;
        this.dailyLimit = dailyLimit;
    }

    public void checkAndConsume(String sub) {
        // TTL 只在首次创建时设（跨时区余量，日切后自然换新 key）——该语义在 KvStore 里
        long n = store.increment("quota:" + sub + ":" + LocalDate.now(), Duration.ofHours(26));
        if (n > dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "日请求配额已用尽（" + dailyLimit + "/天），明日重置或联系管理员");
        }
    }

    /** 只读水位（Ops Console）：当日已用；键不存在返回 0，绝不 INCR（读不得消耗配额）。 */
    public long usedToday(String sub) {
        return store.readLong("quota:" + sub + ":" + LocalDate.now());
    }

    public long dailyLimit() { return dailyLimit; }
}
