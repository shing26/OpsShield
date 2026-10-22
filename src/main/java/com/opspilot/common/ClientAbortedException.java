package com.opspilot.common;

/**
 * 客户端已断开（G1，2026-10-22）：网关在 token 检查点主动抛出，用于停止继续消费上游流。
 *
 * <p>放在 common 而不是 gateway：LLM 客户端必须能原样重抛它（不能被包装成"上游失败"），
 * 否则一次客户端取消会被计入网络错误/熔断，把"用户走了"误判成"模型挂了"。
 * 继承 RuntimeException：LLM 的 {@code Consumer<String>} 回调签名不能抛受检异常。
 */
public class ClientAbortedException extends RuntimeException {
    public ClientAbortedException(String message) {
        super(message);
    }
}
