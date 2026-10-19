package com.opspilot.retrieval;

/**
 * 身份硬过滤的**单一事实源**（ADR-0008 / ADR-0022 决策 4）。
 *
 * <p>为什么要有这个类：过滤条件此前以三种形态各写了一遍——ES 的 {@code term + range} 查询对象、
 * Qdrant 的 gRPC {@code Filter}、以及（ADR-0022 新增的）内存索引的谓词。三份实现表达的是同一件事，
 * 而**只要有一份写反，泄漏就发生在一条演示看不出来的路径上**。故把"这件事是什么"提取成
 * 字段名常量 + 一个可执行谓词：内存侧直接调 {@link #matches}，ES/Qdrant 侧由
 * {@code IdentityFilterContrastTest} 现算比对（那两侧必须构造出查询对象，无法复用谓词本身，
 * 但可以断言它们构造出的**字段名与算子**与这里一致）。
 *
 * <p><b>fail-closed</b>：请求方 tenant 为 null/空时**一律不匹配**——上游鉴权（{@code JwtAuthFilter}）
 * 缺失/空/超长 tenant 本就 401，这里是纵深防御：万一有绕过 filter 的调用路径，
 * 得到的是空集而不是"全部文档"。
 *
 * <p>auth_level 语义是 {@code doc.level <= user.level}（低密级可见高密级？不——是**密级号小的
 * 文档对密级号大的用户可见**）。level 0/-5 等越界值天然命中空集（库内文档 auth_level>=1），
 * 与 ES/Qdrant 两侧的钳制口径一致。
 */
public final class IdentityFilter {

    public static final String TENANT_FIELD = "metadata.tenant";
    public static final String AUTH_LEVEL_FIELD = "metadata.auth_level";

    private IdentityFilter() {
    }

    /** 该文档对 (tenant, authLevel) 这个身份是否可见。 */
    public static boolean matches(String requestTenant, int requestAuthLevel,
                                  String docTenant, int docAuthLevel) {
        return requestTenant != null && !requestTenant.isBlank()
                && requestTenant.equals(docTenant)
                && docAuthLevel <= requestAuthLevel;
    }
}
