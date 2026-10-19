package com.opspilot.gateway;

/**
 * 成功交付的 TTFT 计时值。firstTokenNano 为首个正文增量的纳秒时刻。
 */
public record TtftTiming(long t0, long firstTokenNano) {}
