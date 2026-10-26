package com.opspilot.gateway;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 错误响应体的**单一出口**：全仓只有两个形状。
 *
 * <p>为什么必须收敛到一处：2026-09-28 的探索性验收（Persona QA）实测到**四种**形状同时在跑——
 * `/v1` 的 OpenAI 信封、`/api/**` 的 `{code,message}`、Spring 默认错误体（`sendError` 出来的
 * `{timestamp,status,error,path}`）、以及登录面 Spring ErrorResponse 渲染的 `application/problem+json`。
 * 而文档只声明了两种。写客户端的人要为四种形状各写一条解析分支；更麻烦的是**同类缺陷已经不是第一次**
 * （QA 第五轮 F1/F2 修过一批错误分层），根因就是"每个面各自拼 JSON"——修一处漏一处。
 *
 * <p>所以本类把形状定义收成一份，所有出口（filter 短路、controller advice、登录面）都从这里出。
 * 判定"该用哪一种"只看一点：**路径是否以 `/v1` 开头**（OpenAI 兼容面要信封，其余面要 `{code,message}`）。
 * 这也与 `GlobalExceptionHandler` 既有的分流口径一致，本类只是把它提取成可复用的一处。
 *
 * <p>两个形状的字段顺序被刻意固定（`LinkedHashMap`）：改动会体现在响应字节上，而验收用例按字节断言。
 */
public final class ErrorBodies {

    private ErrorBodies() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 非 `/v1` 面的错误体：`{"code":...,"message":...}`。 */
    public static void writeApi(HttpServletResponse resp, int status, String code, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        writeRaw(resp, status, MAPPER.writeValueAsString(body));
    }

    /**
     * `/v1` 面的错误体（OpenAI 规范）：`{"error":{"message":...,"type":...}}`。
     *
     * @param code OpenAI 规范里 code 是机器码或 null——只有 401（`invalid_api_key`）与 429
     *             （`rate_limit_error`）的专属路径携带；400/415/500 传 null 不落该字段，
     *             免得把 HTTP 状态码字符串塞进去造成语义漂移（QA P3 的既有裁决）。
     */
    public static void writeOpenAi(HttpServletResponse resp, int status, String message, String type,
                                   String code) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        err.put("type", type);
        if (code != null) err.put("code", code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", err);
        writeRaw(resp, status, MAPPER.writeValueAsString(body));
    }

    private static void writeRaw(HttpServletResponse resp, int status, String json) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(json);
    }
}
