package com.opspilot.chunk;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 与离线 chunks.jsonl 完全同构的语义块契约（ADR-0001 接缝）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Chunk(
        String chunkId,
        String docId,
        String type,
        String text,
        String breadcrumb,
        Metadata metadata
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(
            String service,
            String endpoint,
            String method,
            List<String> errorCodes,
            int authLevel,
            String env,
            String tenant,
            /** L1 受控恢复动作的显式声明（ADR-0028）：chunk 标注 L1-write-low 才进入 L1 通道；
             *  旧语料无此字段 → null → 非 L1（不是语料里的写命令都自动成为契约）。 */
            String actionClass,
            /** 文档版本身份（ADR-0039）：正文 sha256 前 12 位；旧语料无此字段 → null → 引用显示"版本未知"。 */
            String docDigest,
            /** 最近一次人工审查日期（YYYY-MM-DD）；null = 未标注，绝不默认新鲜。 */
            String reviewedAt
    ) {
        /** 旧 8 参兼容构造：S3 之前的位置参数调用点（测试夹具）无需逐个补 null。 */
        public Metadata(String service, String endpoint, String method, List<String> errorCodes,
                        int authLevel, String env, String tenant, String actionClass) {
            this(service, endpoint, method, errorCodes, authLevel, env, tenant, actionClass, null, null);
        }
    }
}
