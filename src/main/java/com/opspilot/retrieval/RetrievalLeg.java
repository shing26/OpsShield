package com.opspilot.retrieval;

import java.util.List;

/**
 * 一条检索腿（ADR-0022 的检索接缝）：给定 query 与**调用者身份**，返回候选切片。
 *
 * <p>接口只有一个方法，因为腿之间唯一必须一致的就是这件事——身份过滤是腿的**职责**而不是
 * 编排层的后置裁剪（ADR-0008：权限维度挂在每条共享路径上）。live 下是 ES 与 Qdrant，
 * memory 下是同一份内存索引的两种打分；四个实现都必须先按 {@link IdentityFilter} 过滤再打分。
 */
public interface RetrievalLeg {

    List<ScoredChunk> search(String query, String tenant, int authLevel, int topK) throws Exception;
}
