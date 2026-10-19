package com.opspilot.ingest;

import java.util.Arrays;
import java.util.List;

/**
 * 入库前向量健全性校验（A2-8 根因护栏）。
 *
 * <p>2026-10-17 的 active Qdrant 集合曾出现 448 个点全部为同一个常向量
 * （每个分量都是 {@code 1/sqrt(1024)}）。查询向量与库内向量近似正交，向量腿退化为噪声，
 * RRF 融合把 ES 精确命中的 level-3 块挤出 final-top-3，A2-8 因此稳定红。
 *
 * <p>现有的 staging 计数验收（448 == 448）只看条数，看不见"每条都写坏了"。
 * 这里在换别名前补一层形状与退化检查：任何异常直接抛出，入库失败，旧别名继续服务。
 */
public final class EmbeddingSanity {

    private static final float EPS = 1e-6f;

    private EmbeddingSanity() {}

    /**
     * @param vectors       实时 embedding 结果
     * @param expectedCount 期望条数（= chunk 数）
     * @param expectedDim   期望维度（= 集合向量维度）
     * @throws IllegalStateException 任一条缺失/维度错误/含非有限值/零向量/常向量，
     *                               或全部向量完全相同
     */
    public static void check(List<float[]> vectors, int expectedCount, int expectedDim) {
        if (vectors == null || vectors.size() != expectedCount) {
            throw new IllegalStateException("embedding 条数与 chunk 不一致: "
                    + (vectors == null ? "null" : vectors.size()) + " != " + expectedCount);
        }
        float[] first = null;
        boolean distinctFromFirst = false;
        for (int i = 0; i < vectors.size(); i++) {
            float[] v = vectors.get(i);
            if (v == null || v.length != expectedDim) {
                throw new IllegalStateException("embedding 维度异常: index=" + i
                        + " len=" + (v == null ? "null" : v.length) + " expected=" + expectedDim);
            }
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            double norm = 0;
            for (float x : v) {
                if (!Float.isFinite(x)) {
                    throw new IllegalStateException("embedding 含非有限值: index=" + i);
                }
                min = Math.min(min, x);
                max = Math.max(max, x);
                norm += (double) x * x;
            }
            if (norm <= (double) EPS * EPS) {
                throw new IllegalStateException("embedding 为零向量: index=" + i);
            }
            if (max - min <= EPS) {
                throw new IllegalStateException("embedding 为常向量（全等分量）: index=" + i);
            }
            if (i == 0) {
                first = v;
            } else if (!Arrays.equals(first, v)) {
                distinctFromFirst = true;
            }
        }
        if (expectedCount > 1 && !distinctFromFirst) {
            throw new IllegalStateException("embedding 全部相同（退化索引）: n=" + expectedCount);
        }
    }
}
