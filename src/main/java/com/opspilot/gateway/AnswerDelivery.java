package com.opspilot.gateway;

/**
 * 正文已经由编排流式下发，或由 sink 从完成载荷缓冲式下发。
 */
public enum AnswerDelivery {
    STREAMED,
    BUFFERED
}
