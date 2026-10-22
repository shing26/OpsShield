package com.opspilot.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求体大小上限（G0-W0.1，2026-10-22 运维审查）：{@code /api/**} 与 {@code /v1/**} 的
 * 请求体一律 ≤1MB，超限直接 413，且错误体走 {@link ErrorBodies}（与全仓其余错误同形状）。
 *
 * <p><b>为什么要在容器之外再拦一道</b>：Tomcat 的 {@code max-swallow-size} 只是"读不完时
 * 最多吞多少再断开"，不是请求体上限——没有上限时一个 1GB 的 JSON 会被 Jackson 一路读进内存，
 * 在虚拟线程模式下每个请求再各占一份，堆没打满之前 CPU 先被 JSON 解析吃光。上限是 1MB：
 * 本服务所有请求体都是短 JSON（排障 query 上限 8KB、OpenAI 兼容面是短消息），1MB 留了两个
 * 数量级的余量，同时让恶意大包在入口即被拒。
 *
 * <p><b>两种长度来源必须都覆盖</b>：
 * <ul>
 *   <li>{@code Content-Length} 超限：不读 body，直接 413（最省资源，也避免把恶意大包读进来）；</li>
 *   <li>{@code Transfer-Encoding: chunked}（无 Content-Length）：用<b>计数流</b>边读边计数，
 *       累计超过 1MB 立刻 413——不能信"没有长度声明就一定是小包"。</li>
 * </ul>
 *
 * <p><b>实现取舍：缓冲而非"边读边抛异常"</b>。超过上限时如果让输入流在 Jackson 读取中途抛
 * {@code IOException}，Spring 会把它包成 {@code HttpMessageNotReadableException} → 落成 400
 * "JSON 格式错误"——状态码和文案都在撒谎（不是格式错，是太大），而且 {@code GlobalExceptionHandler}
 * 拿不到原始原因。所以这里先把 body 读入内存（最多 1MB+1 字节，超限即停），再以缓存请求包装
 * 传给下游；超限路径由本过滤器自己写 413，形状不需要另写一份。
 *
 * <p>顺序：在 {@code RequestIdFilter}（HIGHEST_PRECEDENCE）之后——413 日志同样带 request_id；
 * 在鉴权与会话之前——拒绝不该等鉴权（未认证的超大包同样不该被读）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    /** 1MB：本服务所有请求体都是短 JSON，见类注释。 */
    public static final long MAX_BODY_BYTES = 1024L * 1024L;

    /** 413 不在 Servlet 的 {@code SC_*} 常量表里（Servlet 6 只有 SC_CONTENT_TOO_LARGE 的新名），本地命名避免裸魔数。 */
    private static final int SC_PAYLOAD_TOO_LARGE = 413;

    private static final int BUFFER_SIZE = 8192;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!isGuardedPath(path)) {
            chain.doFilter(req, resp);
            return;
        }

        long declared = req.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            // 声明就超限：连读都不读，直接拒（也避免把一个恶意大包读进内存）
            reject(req, resp, declared);
            return;
        }
        if (declared == 0 || !mayHaveBody(req.getMethod())) {
            chain.doFilter(req, resp);
            return;
        }

        byte[] body = readBodyUpToLimit(req.getInputStream());
        if (body == null) {
            reject(req, resp, -1L);   // chunked/未知长度且实际超过上限
            return;
        }
        chain.doFilter(new CachedBodyRequest(req, body), resp);
    }

    /** 守卫面与 JwtAuthFilter 的鉴权面同口径：业务 API 与 OpenAI 兼容面（静态面板不在内）。 */
    private static boolean isGuardedPath(String path) {
        return path.startsWith("/api/") || path.startsWith("/v1");
    }

    /** GET/HEAD/OPTIONS 没有请求体语义；其余方法即使声明的长度为未知也按 body 处理。 */
    private static boolean mayHaveBody(String method) {
        return !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method));
    }

    /**
     * 计数流：边读边累计，超过上限立即返回 {@code null}（由调用方写 413）。
     * 返回 null 是刻意的——"超限"与"读失败"必须区分，前者是 413，后者才该冒泡。
     */
    private static byte[] readBodyUpToLimit(ServletInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE);
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 413：形状交给 ErrorBodies（/v1=OpenAI 信封，其余=code/message），不在此另拼 JSON。 */
    private void reject(HttpServletRequest req, HttpServletResponse resp, long declared) throws IOException {
        log.warn("请求体超限已拒绝: {} {} declared={} limit={}",
                req.getMethod(), req.getRequestURI(), declared, MAX_BODY_BYTES);
        String msg = "请求体超过 1MB 上限（request body exceeds 1MB limit）";
        if (req.getRequestURI().startsWith("/v1")) {
            ErrorBodies.writeOpenAi(resp, SC_PAYLOAD_TOO_LARGE, msg, "invalid_request_error", null);
            return;
        }
        ErrorBodies.writeApi(resp, SC_PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", msg);
    }

    /**
     * 带缓存 body 的请求包装：下游（Jackson/MVC）照常从流里读，行为与未包装时一致。
     * 只覆盖 body 读取相关方法——其余一律委托原请求，避免"包装器吞掉某个容器特性"。
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private final ByteArrayInputStream delegate = new ByteArrayInputStream(body);

                @Override
                public int read() {
                    return delegate.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return delegate.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return delegate.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步链路不使用异步读取；留空以免触发 IllegalStateException
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
