package com.opspilot.gateway;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.gateway.dto.ChatRequest;
import com.opspilot.resilience.AdmissionGate;
import jakarta.validation.Validator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * OpenAI 兼容面（/v1，ADR-0007）：任何标准 OpenAI 客户端（LobeChat/Dify/SDK）可直连本排障网关。
 * 身份模型=「API Key 即 OpsPilot JWT」：/v1 在 JwtAuthFilter 守卫内，租户/密级/吊销/配额/审计
 * 经同一 ChatOrchestrator 全量穿透——这不是第二套系统，是同一编排的第二个协议面。
 * 有状态性声明：无状态单轮语义（取最后一条 user 消息，忽略 system/历史——检索按单 query
 * 指纹设计，多轮请由客户端把上下文并入单条消息），取舍见 ADR-0007。
 * content 类型边界（QA 第五轮 P2）：String 直用；官方 content parts 数组仅取 type=text 拼接
 * （多模态模板/LobeChat 必发）；数字等其余类型 400 拒绝——静默强转会烧真实配额。
 */
@RestController
@RequestMapping("/v1")
public class OpenAiController {

    static final String DEFAULT_MODEL = "opspilot";

    /** OpenAI chat.completions 请求体（仅取所需字段，未知字段忽略）。 */
    public record Msg(String role, Object content) {}
    public record ChatCompletionsReq(String model, List<Msg> messages, boolean stream) {}

    private final ChatOrchestrator orchestrator;
    private final BuildProperties build; // /v1/models 的 created 字段源（无 build-info 时 0）
    private final AdmissionGate admission;
    private final Validator validator;

    public OpenAiController(ChatOrchestrator orchestrator,
                            @Nullable BuildProperties build,
                            AdmissionGate admission,
                            Validator validator) {
        this.orchestrator = orchestrator;
        this.build = build;
        this.admission = admission;
        this.validator = validator;
    }

    @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatCompletions(@RequestBody ChatCompletionsReq req,
                                      HttpServletRequest http, HttpServletResponse resp) {
        UserContext user = JwtAuthFilter.from(http);
        if (!req.stream()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "this endpoint only supports streaming (stream=true) completions / 本端点仅提供流式补全");
        }
        String query = lastUserMessage(req.messages());
        // #18：字段级契约与 /chat/stream 是同一份（ChatRequest 上的注解），但这里手工 new record
        // 绕过了 MVC 的参数校验——必须显式 validate。否则 9000 字 query 会一路打进上游，4xx
        // 连续失败达阈后把**全租户**推进 L2 熔断（ADR-0026 决策 4 要防的正是这种单点放大）。
        ChatRequest chatRequest = new ChatRequest(query, "manual", "", "prod", null);
        var violations = validator.validate(chatRequest);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
        }
        // #31：先拿准入许可、再扣配额（被准入拒绝的请求不得消耗当日配额）
        AdmissionGate.Permit permit = admission.tryAcquire(user.tenantId());
        if (permit == null) {
            orchestrator.recordAdmissionRejected();
            resp.setHeader("Retry-After", String.valueOf(admission.retryAfterSeconds()));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "too many concurrent requests for this tenant / 当前租户并发已满，请稍后重试");
        }
        try {
            orchestrator.precheck(user);
        } catch (RuntimeException e) {
            permit.release();
            throw e;
        }
        // Cache-Control/X-Accel-Buffering：防中间代理缓冲破坏打字机（QA 第五轮 P3 线卫生）
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        // 模型恒回真实后端名：请求里的 model 乱值不回显（谎言回显会误导监控归因）
        SseEmitter emitter = new SseEmitter(300_000L);
        // 本面恒 source=manual：协议体的角色是"人在客户端里说话"，告警链路只走 /chat/stream
        // （自举告警源经那条入口，ADR-0011）。若将来有人把生产者指到本面，审计会把告警静默
        // 标成 manual，且 service="" / env="prod" 会与 SSE 面算出不同指纹——两处都是坑。
        orchestrator.submit(chatRequest,
                user, new OpenAiChatSink(emitter, DEFAULT_MODEL), "openai", permit);
        return emitter;
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of("object", "list",
                "data", List.of(Map.of(
                        "id", DEFAULT_MODEL,
                        "object", "model",
                        "created", createdEpoch(),
                        "owned_by", "opspilot-gateway")));
    }

    private long createdEpoch() {
        if (build == null || build.getTime() == null) return 0L;
        return build.getTime().getEpochSecond();
    }

    private static String lastUserMessage(List<Msg> messages) {
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Msg m = messages.get(i);
                if (m == null || !"user".equals(m.role())) continue;
                String text = contentText(i, m.content());
                if (text != null) return text;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "messages must contain a non-empty user message / messages 中缺少非空的 user 消息");
    }

    /** String 直用；text-parts 数组拼接；其余类型/拼接后空白 → null（跳过）或 400（类型明确非法）。 */
    private static String contentText(int index, Object content) {
        if (content == null) return null;
        if (content instanceof String s) return s.isBlank() ? null : s;
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                if (p instanceof Map<?, ?> part && "text".equals(part.get("type"))
                        && part.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            return sb.toString().isBlank() ? null : sb.toString();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "messages[" + index + "].content must be a non-empty string or text-part array"
                        + " / content 需为非空字符串或 text parts");
    }
}
