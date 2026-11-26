package com.opspilot.retrieval;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 引用时效判定（ADR-0039）：纯函数，输入审查日期与"今天"，输出 fresh / stale / unknown。
 *
 * <p><b>为什么缺失与非法一律 unknown，而不是 fresh</b>：默认新鲜会让"没人签字"的文档
 * 在面板上看起来和刚审查过的一样，这是静默的错误信号。unknown 才是诚实的"没有事实"。
 *
 * <p><b>为什么未来日期也判 unknown</b>：未来日期是数据错误（时区/手误），
 * 把它当 fresh 等于让一条坏数据长期霸占"最新"；判 unknown 会让人看见并修数据。
 *
 * <p>时间基准由调用方传入（当前实现用 UTC 日期）：本类不读系统时钟，单测才能固定日期；
 * 180 天线上的 UTC 日界最多带来 1 天偏差，可接受。
 */
public final class CitationFreshness {

    public static final String FRESH = "fresh";
    public static final String STALE = "stale";
    public static final String UNKNOWN = "unknown";

    private CitationFreshness() {}

    public static String classify(String reviewedAt, LocalDate today, int staleAfterDays) {
        if (reviewedAt == null || reviewedAt.isBlank() || today == null || staleAfterDays < 0) {
            return UNKNOWN;
        }
        final LocalDate reviewed;
        try {
            reviewed = LocalDate.parse(reviewedAt.trim());
        } catch (DateTimeParseException e) {
            return UNKNOWN;
        }
        if (reviewed.isAfter(today)) return UNKNOWN;
        return ChronoUnit.DAYS.between(reviewed, today) <= staleAfterDays ? FRESH : STALE;
    }
}
