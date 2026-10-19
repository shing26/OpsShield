package com.opspilot.gateway.dto;

import com.opspilot.action.Action;
import java.util.List;

/**
 * 缓存/Single-Flight 共享的答案载荷。
 * 命名口径为刻意决策：本类型是 Redis/Qdrant 内的**存储格式**（Jackson 默认 camelCase），
 * 从不直接上线；对外 SSE 帧的 snake_case 契约由 SseEvents 手工构 Map。
 * 不做统一——统一需迁移已存缓存或引入 @JsonProperty 双面映射，收益为零且引入兼容风险。
 *
 * <p><b>refused 是存储合同的一部分（2026-09-28，live QA F-1）</b>：标记"这份载荷是拒答/澄清
 * 而非排障答案"。为什么必须显式：`replay()`（缓存与 Single-Flight 回放路径）的审计行此前
 * **硬编码 `refused=false`**——一条被拒答并写入 L1 的 query，2h TTL 内重发时走回放，
 * 客户端收到的仍是拒答话术，审计却记成"未拒答、触达密级=N"（live 实测三次复现，
 * `daily_usage` 的 refuse_rate 因此被系统性低估）。原始 boolean：旧缓存 JSON 缺该字段时
 * 反序列化为 false（L1 TTL 2h 内自然消失；L2 从不存拒答，无残窗）。
 *
 * <p><b>actions 是只读行动契约（ADRs 0017）</b>：从 refs 引用的同一批 chunk 里逐字抽出的
 * 只读诊断命令清单，与 refs 同源同权——因此它跟着载荷一起进 L1/L2，回放路径零额外成本也
 * 零漂移。**兼容性**：字段加在既有存储格式尾部，旧缓存 JSON 反序列化时它是 null，消费侧一律
 * 走 {@link #actionsOrEmpty()}（2h TTL 内自然换代，不写迁移脚本）。
 *
 * <p><b>nearMiss 是拒答时的"最接近的候选"（ADR-0023）</b>：只带 {@code service} 与
 * {@code error_codes} 两类**标识符**，不带 chunk_id / 面包屑 / 正文——它给的是"下一步该问什么"，
 * 不是"低置信度的答案"。与 actions 同样的兼容策略：尾部字段、旧缓存为 null、消费侧走
 * {@link #nearMissOrEmpty()}。**为什么必须进载荷**：拒答会写 L1（省一次检索），若只把它放在
 * 生成路径上，同键重发走回放时就丢了导航——那正是 live QA F-1 那类"回放与首答不一致"的复刻。
 */
public record AnswerPayload(
        String answer,
        List<Ref> refs,
        List<Action> actions,
        String mode,
        boolean fastPath,
        int maxAuthLevel,
        String tenant,
        boolean refused,
        List<NearMiss> nearMiss
) {
    public record Ref(String chunkId, String breadcrumb, String service) {}

    /** 拒答时的候选标识符（ADR-0023）：{@code service} + {@code errorCodes}，两者都可为空。 */
    public record NearMiss(String service, List<String> errorCodes) {}

    /** 旧缓存（JSON 无 actions 字段）反序列化后为 null：回放/审计侧统一走这个取值口。 */
    public List<Action> actionsOrEmpty() {
        return actions == null ? List.of() : actions;
    }

    /** 同上：旧缓存没有 nearMiss 字段，一律走这个取值口。 */
    public List<NearMiss> nearMissOrEmpty() {
        return nearMiss == null ? List.of() : nearMiss;
    }

    /**
     * 协议面的**闸门**标记（ADR-0020 注记 / ADR-0023）：{@code clarify | refuse | none}。
     *
     * <p>派生自本载荷自己的两个状态字段，**刻意不新增存储字段**——多存一个就有第三处能与之打架，
     * 而"帧上的状态与话术不一致"正是 2026-09-28 live QA F-1 那类失真的形态。派生规则：
     * 回指澄清（{@code mode="clarify"}）优先，其次 {@code refused}。
     */
    public String gate() {
        if ("clarify".equals(mode)) return "clarify";
        return refused ? "refuse" : "none";
    }
}
