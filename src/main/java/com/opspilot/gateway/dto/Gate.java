package com.opspilot.gateway.dto;

/** 本次请求的闸门状态。领域层只使用这个封闭枚举，线格式由协议边界投影。 */
public enum Gate {
    NONE,
    CLARIFY,
    REFUSE
}
