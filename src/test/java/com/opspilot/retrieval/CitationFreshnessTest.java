package com.opspilot.retrieval;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** S3 时效判定的边界锁（AC7）：缺失/非法/未来一律 unknown，阈值边界含等号。 */
class CitationFreshnessTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    private static final int DAYS = 180;

    @Test
    void missingOrBlankIsUnknown() {
        assertEquals("unknown", CitationFreshness.classify(null, TODAY, DAYS));
        assertEquals("unknown", CitationFreshness.classify("", TODAY, DAYS));
        assertEquals("unknown", CitationFreshness.classify("   ", TODAY, DAYS));
    }

    @Test
    void invalidDatesAreUnknown() {
        assertEquals("unknown", CitationFreshness.classify("2026-02-30", TODAY, DAYS));
        assertEquals("unknown", CitationFreshness.classify("2026-9-6", TODAY, DAYS));
        assertEquals("unknown", CitationFreshness.classify("yesterday", TODAY, DAYS));
    }

    @Test
    void futureDateIsUnknownNotFresh() {
        assertEquals("unknown", CitationFreshness.classify("2026-11-26", TODAY, DAYS),
                "未来日期是数据错误，不能伪装成最新");
    }

    @Test
    void thresholdBoundaryIsInclusive() {
        assertEquals("fresh", CitationFreshness.classify("2026-05-29", TODAY, DAYS), "恰好 180 天仍新鲜");
        assertEquals("stale", CitationFreshness.classify("2026-05-28", TODAY, DAYS), "181 天判过期");
    }

    @Test
    void todayIsAlwaysFresh() {
        assertEquals("fresh", CitationFreshness.classify("2026-11-25", TODAY, 0));
        assertEquals("stale", CitationFreshness.classify("2026-11-24", TODAY, 0));
    }
}
