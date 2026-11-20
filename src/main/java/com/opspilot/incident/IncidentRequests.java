package com.opspilot.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 事故 API 的请求体。长度上限与前端输入上限一致，服务端再兜一层。 */
public final class IncidentRequests {

    private IncidentRequests() {}

    /**
     * 创建事故。{@code clientId} 是幂等键：前端为每次"新建"生成 UUID，网络重试不会造出两张单。
     * 缺省时服务端生成（仍然一单一键，只是失去跨重试去重能力）。
     */
    public record Create(
            @Size(max = 64, message = "clientId 超长（上限 64）") String clientId,
            @Size(max = 120, message = "title 超长（上限 120）") String title,
            @NotBlank(message = "query 不能为空")
            @Size(max = 8192, message = "query 超长（上限 8192）") String query,
            @Size(max = 128, message = "service 超长（上限 128）") String service,
            @Size(max = 64, message = "errorCode 超长（上限 64）") String errorCode,
            @Size(max = 32, message = "env 超长（上限 32）") String env,
            @Size(max = 128, message = "alertId 超长（上限 128）") String alertId,
            @Pattern(regexp = "manual|alert", message = "source 只能是 manual 或 alert") String source,
            @Pattern(regexp = "|P1|P2|P3", message = "priority 只能是空 / P1 / P2 / P3") String priority) {}

    /**
     * 补丁语义：**null = 不改**；空串 = 清空（owner 即取消分配，status 会按空串拒绝）。
     * 这样前端可以只发变化字段，而不用把整行回传（回传会放大并发覆盖窗口）。
     */
    public record Patch(
            @Size(max = 120, message = "title 超长（上限 120）") String title,
            @Size(max = 8192, message = "query 超长（上限 8192）") String query,
            @Size(max = 128, message = "service 超长（上限 128）") String service,
            @Size(max = 64, message = "errorCode 超长（上限 64）") String errorCode,
            @Size(max = 32, message = "env 超长（上限 32）") String env,
            @Size(max = 128, message = "alertId 超长（上限 128）") String alertId,
            @Pattern(regexp = "open|closed", message = "status 只能是 open 或 closed") String status,
            @Size(max = 64, message = "owner 超长（上限 64）") String owner,
            @Pattern(regexp = "|P1|P2|P3", message = "priority 只能是空 / P1 / P2 / P3") String priority,
            @Size(max = 262144, message = "diagnosisJson 超长（上限 256KB）") String diagnosisJson,
            @Size(max = 131072, message = "timelineJson 超长（上限 128KB）") String timelineJson,
            @Size(max = 500, message = "closeNote 超长（上限 500）") String closeNote) {}
}
