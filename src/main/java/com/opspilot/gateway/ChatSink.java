package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;

/**
 * 聊天编排的事件出口（Sink 抽象）：编排逻辑（缓存/风暴收敛/降级/检索/生成）与
 * 线格式解耦——SSE 自定义事件帧（SseChatSink → SseEvents）与 OpenAI chunk 帧
 * （OpenAiChatSink）是同一编排的两种投递方式。新增协议面 = 新增一个 sink，
 * 编排链路（含 L1/L2/Single-Flight/熔断/审计）零复制零漂移。
 */
public interface ChatSink {

    /**
     * 编排元信息（指纹/缓存命中/降级态/快路径/检索耗时/**是否拒答**）。OpenAI 帧无对应位可不落。
     *
     * <p>{@code refused} 是**用户可见状态**而非内部量纲（用户本来就该知道自己被拒答了），故与
     * 「分段耗时」条"内部耗时不该泄到对外协议面"不冲突（ADR-0020）。它的值一律取自本轮
     * {@link AnswerPayload#refused()}——**回放路径也取载荷里的值**，不硬编码，否则会复刻
     * 2026-09-28 live QA F-1 那类"客户端收到拒答话术、审计却记未拒答"的失真。
     * 注：回指澄清（{@code mode="clarify"}）在载荷里同样标 {@code refused=true}（见 CONTEXT.md）。
     *
     * <p>{@code gate} 是**哪道闸**拦下的（ADR-0023）：{@code refused}
     * 只说"没答"，说不清"为什么没答"——回指澄清（零检索零 LLM）与无据拒答（检索跑完但低于阈值）
     * 的成因与代价都不同，而 CONTEXT.md 明确写着"澄清不是拒答"。同样一律取自
     * {@link AnswerPayload#gate()}（回放路径不硬编码）。
     */
    void meta(Meta meta);

    /** 增量文本帧。 */
    void delta(String token);

    /** 唯一终态：成功完成或失败。sink 负责按协议呈现并完成流。 */
    void deliver(DeliveryOutcome outcome);
}
