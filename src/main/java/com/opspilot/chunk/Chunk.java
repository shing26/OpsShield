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
            String actionClass
    ) {}
}
