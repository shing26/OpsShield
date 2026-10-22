package com.opspilot.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 安全响应头（G0-W0.1，2026-10-22 运维审查）：给所有响应加一组"零成本、默认关闭"的浏览器侧防护。
 *
 * <p>为什么放在网关而不是交给反向代理：本项目的部署形态包含"裸机直连 8081"（scripts/start_gateway.sh
 * 与 README 的默认形态），此时没有反代来补这些头；而面板是单文件 HTML + 内联脚本（ADR-0009），
 * 一旦被嵌入 iframe 或跨源引用，点击劫持/内容嗅探就没有第二道防线。默认绑 127.0.0.1 是本轮另一处修正，
 * 但"默认安全"不能依赖部署者永远记得配反代。
 *
 * <p>四个头各自防一件事：
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff}：禁止浏览器按内容猜类型（错误页/JSON 被当 HTML 执行）；</li>
 *   <li>{@code X-Frame-Options: DENY}：面板不得被任何页面 iframe 嵌套（点击劫持）；</li>
 *   <li>{@code Referrer-Policy: no-referrer}：面板 URL 不随外链泄露（避免把内网路径/查询串带到外部）；</li>
 *   <li>{@code Permissions-Policy}：显式关闭定位/麦克风/摄像头——本服务一个都不用，关闭即缩小攻击面。</li>
 * </ul>
 *
 * <p>只做"加头"不做"删头"：不覆盖应用已设置的同名头（如反代已经给了更严格策略），保持可叠加。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** 所有响应统一附加的防护头（含 /actuator 与静态面板）。 */
    private static final String[][] HEADERS = {
            {"X-Content-Type-Options", "nosniff"},
            {"X-Frame-Options", "DENY"},
            {"Referrer-Policy", "no-referrer"},
            {"Permissions-Policy", "geolocation=(), microphone=(), camera=()"},
    };

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        // 在 chain 之前写入：异常路径（advice 直写 response）同样带上头，不会因"出错提前返回"而漏防护
        for (String[] header : HEADERS) {
            if (!resp.containsHeader(header[0])) {
                resp.setHeader(header[0], header[1]);
            }
        }
        chain.doFilter(req, resp);
    }
}
