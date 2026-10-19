package com.opspilot.retrieval;

/**
 * 向量相似度的**单一事实源**（ADR-0022）。
 *
 * <p>memory 模式下有两处需要算余弦：向量腿（检索）与 L2 语义缓存。两处各写一份会让
 * "同样的输入，缓存说命中、检索说没命中"变成可能，而那种分叉在演示里看不出来。
 * 更关键的是边界口径必须一致：**长度不等返回 0**——宁可当"没分"，也不做半截比较
 * （换过 embedding 维度、或旧缓存条目残留时就是这个形状）。
 */
public final class Vectors {

    private Vectors() {
    }

    /** 余弦相似度；长度不等或任一向量为零向量时返回 0。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
