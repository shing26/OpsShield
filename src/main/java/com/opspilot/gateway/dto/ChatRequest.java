package com.opspilot.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 排障请求（单链路，source 区分告警/人工，ADR-0004）。
 *
 * <p><b>alertId = 告警的权威身份（2026-10-17，告警身份/查询指纹拆分）</b>：Alertmanager 接线的
 * 首次实机验收实证了一处契约错配——规则模板把 {@code {{ $value }}} 渲染进注解，同一条告警
 * 每次重发的文案都带一个新数字，而指纹直接吃 query 全文，于是**同一个故障每次评估都是新指纹**，
 * L1 与 SingleFlight 双双失效（台账见 {@code docs/qa/2026-10-17-alertmanager-intake.md} §3.1）。
 *
 * <p>解法不是让某一层去"猜哪些数字该归一化"，而是把两件事拆开：
 * <b>「这条告警是什么」由调用方显式给出，服务端不再从文案里推断</b>。
 * 真正做聚合的是 Alertmanager，它知道告警的身份；服务端只负责用。
 *
 * <p>契约三条：
 * <ul>
 *   <li>可选。为空则退回文本指纹（{@code FingerprintService.fingerprint}），**与今天逐字相同**——
 *       现有 {@code watchdog_alerts.py} / {@code alert_producer.py} 不传该字段，行为不变。</li>
 *   <li>形状受约束：它要进缓存键组分，故与 {@code X-Trace-Id} 同族纪律（非法即 400 并留痕）。
 *       <b>空白按未提供</b>处理——不少客户端会把可选字段序列化成空串，那不是身份。</li>
 *   <li>服务端视其为**不透明身份**——不解析、不校验含义，只 {@code SHA256("alert-id|" + alertId)}。
 *       谁组装（当前是 alert_bridge）谁负责语义；这样服务端不必知道 Alertmanager 的标签体系。</li>
 * </ul>
 */
public record ChatRequest(
        @NotBlank(message = "query 不能为空") String query,
        @Pattern(regexp = "manual|alert", message = "source 仅允许 manual|alert") String source,
        String service,
        String env,
        @Pattern(regexp = "\\s*|[A-Za-z0-9_.=\\-|]{8,128}",
                message = "alertId 应为 8–128 位 [A-Za-z0-9_.=|\\-]；它进缓存键组分，故形状必须受限")
        String alertId
) {
    public String sourceOrDefault() {
        return source == null || source.isBlank() ? "manual" : source;
    }

    /** 空白视为未提供：@Pattern 已放行空白串（X-Trace-Id 同款约定），这里才是"缺席"的归一入口。 */
    public String alertIdOrNull() {
        return alertId == null || alertId.isBlank() ? null : alertId;
    }
}
