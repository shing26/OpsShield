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
        // N-11：无命中就返回 null（由 ChatOrchestrator 的 sop==null 分支给固定兜底文案）。
        // 原先"任意两篇"的兜底被删除：mapValues 顺序不承诺（KvStore 契约），返回哪两篇是
        // 不可复现的随机选择——对排障者不是帮助而是误导，且会让人误以为命中了相关 SOP。
        return sb.isEmpty() ? null : sb.toString();
    }

    private void appendStep(StringBuilder sb, String text) {
        if (text != null) sb.append(text).append("\n\n");
    }
}
