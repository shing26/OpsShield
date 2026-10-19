package com.opspilot.resilience;

import java.util.List;
import org.springframework.stereotype.Service;
import com.opspilot.retrieval.EsSearchService;
import com.opspilot.store.KvStore;

/** Level 2 兜底：从预热好的静态 SOP 止损清单直出，零 LLM 调用（清单存 KvStore，两个后端同语义）。 */
@Service
public class SopFallbackService {

    private final KvStore store;

    public SopFallbackService(KvStore store) {
        this.store = store;
    }

    /** 按查询中的错误码/服务名匹配静态 SOP，返回拼接文本；无匹配返回 null。
     *  P1：所有键按请求方 tenant 分片——L2 降级直出同样不能成为跨租户泄漏面。 */
    public String lookup(String query, String service, String tenant) {
        String ns = "sop:" + (tenant == null ? "" : tenant) + ":";
        String stepsKey = ns + "steps";
        if (store.mapIsEmpty(stepsKey)) return null;
        List<String> codes = EsSearchService.extractErrorCodes(query);
        StringBuilder sb = new StringBuilder();
        if (!codes.isEmpty()) {
            for (String code : codes) {
                for (String docId : store.setMembers(ns + "code:" + code)) {
                    appendStep(sb, store.mapGet(stepsKey, docId));
                }
            }
        }
        if (sb.isEmpty() && service != null && !service.isBlank()) {
            for (String docId : store.setMembers(ns + "service:" + service)) {
                appendStep(sb, store.mapGet(stepsKey, docId));
            }
        }
        if (sb.isEmpty()) {
            // 兜底：返回本租户任意一篇含错误码上下文的 SOP（绝不越租户）
            store.mapValues(stepsKey).stream().limit(2).forEach(v -> appendStep(sb, v));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private void appendStep(StringBuilder sb, String text) {
        if (text != null) sb.append(text).append("\n\n");
    }
}
