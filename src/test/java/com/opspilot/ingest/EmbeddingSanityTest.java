package com.opspilot.ingest;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2-8 根因回归锁：2026-10-17 的坏索引是"448 个点全为同一个 1/sqrt(dim) 常向量"，
 * 但 staging 计数验收 448 == 448 完全通过。这里锁住入库前的退化检查，
 * 保证同类坏索引在别名切换前就被拒绝。
 */
class EmbeddingSanityTest {

    private static float[] vector(float a, float b, float c) {
        return new float[] {a, b, c};
    }

    @Test
    void acceptsDistinctFiniteNonConstantVectors() {
        assertDoesNotThrow(() -> EmbeddingSanity.check(
                List.of(vector(0.1f, 0.2f, 0.3f), vector(-0.4f, 0.5f, 0.6f)), 2, 3));
    }

    @Test
    void rejectsConstantVectorLikeObservedBadIndex() {
        float[] constant = new float[1024];
        java.util.Arrays.fill(constant, 0.03125f);   // 1/sqrt(1024)，实测坏索引的形状
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmbeddingSanity.check(List.of(constant), 1, 1024));
        assertTrue(e.getMessage().contains("常向量"), e.getMessage());
    }

    @Test
    void rejectsAllIdenticalVectors() {
        float[] a = vector(0.1f, 0.2f, 0.3f);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmbeddingSanity.check(List.of(a, a.clone()), 2, 3));
        assertTrue(e.getMessage().contains("全部相同"), e.getMessage());
    }

    @Test
    void rejectsZeroVectorAndNonFiniteValues() {
        assertThrows(IllegalStateException.class,
                () -> EmbeddingSanity.check(List.of(vector(0, 0, 0)), 1, 3));
        assertThrows(IllegalStateException.class,
                () -> EmbeddingSanity.check(List.of(vector(0.1f, Float.NaN, 0.3f)), 1, 3));
    }

    @Test
    void rejectsCountAndDimensionMismatch() {
        assertThrows(IllegalStateException.class,
                () -> EmbeddingSanity.check(List.of(vector(0.1f, 0.2f, 0.3f)), 2, 3));
        assertThrows(IllegalStateException.class,
                () -> EmbeddingSanity.check(List.of(new float[] {0.1f, 0.2f}), 1, 3));
    }
}
