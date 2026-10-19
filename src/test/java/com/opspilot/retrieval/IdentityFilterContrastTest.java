package com.opspilot.retrieval;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import io.qdrant.client.grpc.Points;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **两套实现行为一致**的对照锁（ADR-0022 后果清单里点名要求的那一条）。
 *
 * <p>为什么必须存在：身份过滤现在有三种落法——ES 的查询对象、Qdrant 的 gRPC Filter、
 * 内存索引的谓词。三者表达同一件事，而**只要有一份写反（漏 tenant、算子从 lte 变成 gte、
 * 字段名拼错），泄漏就发生在一条演示看不出来的路径上**：live 演示走 ES/Qdrant，
 * 零依赖演示走内存，两边都不会报错。
 *
 * <p>ES/Qdrant 侧无法复用谓词本身（它们必须构造查询对象），所以这里比对的是**构造出来的
 * 字段名与算子**是否与 {@link IdentityFilter} 的常量/语义一致；内存侧则直接调
 * {@link IdentityFilter#matches}，判据是同一个函数。
 *
 * <p>变异验证（改坏必红）：把 ES 侧 tenant 字段名改成 {@code metadata.tenants} → 用例 2 红；
 * 把 Qdrant 的 {@code lte} 改成 {@code gte} → 用例 3 红；把谓词的 tenant 比较去掉 → 用例 4 红；
 * 把 {@code docLevel <= requestLevel} 改成 {@code >=} → 用例 5 红。
 */
class IdentityFilterContrastTest {

    private static final String TENANT = "tenant-internal";
    private static final int LEVEL = 3;
    /** JsonData 转数值需要 mapper——这是 ES 客户端的要求，与判据本身无关。 */
    private static final JacksonJsonpMapper MAPPER = new JacksonJsonpMapper();

    /** 1. 字段名是常量，不是散落各处的字面量——两个后端都从这一处取。 */
    @Test
    void fieldNamesHaveOneSource() {
        assertEquals("metadata.tenant", IdentityFilter.TENANT_FIELD);
        assertEquals("metadata.auth_level", IdentityFilter.AUTH_LEVEL_FIELD);
    }

    /** 2. ES 侧：term(tenant 等值) + range(auth_level lte)，两条都进 filter。 */
    @Test
    void esBuildsTenantTermAndLevelRange() {
        List<Query> f = EsSearchService.identityFilters(TENANT, LEVEL);
        assertEquals(2, f.size(), "两个维度缺一不可：少一条就是跨租户或提权");

        Query term = f.get(0);
        assertTrue(term.isTerm(), "租户必须走 term（等值），match 会漏");
        assertEquals(IdentityFilter.TENANT_FIELD, term.term().field());
        assertEquals(TENANT, term.term().value().stringValue());

        Query range = f.get(1);
        assertTrue(range.isRange());
        assertEquals(IdentityFilter.AUTH_LEVEL_FIELD, range.range().field());
        assertEquals((double) LEVEL, range.range().lte().to(Double.class, MAPPER),
                "必须是 lte（doc.level <= user.level）；gte 会让低密级用户看到高密级文档");
    }

    /** 3. Qdrant 侧：同样的两个字段、同样的算子（lte 钳到 >=0）。 */
    @Test
    void qdrantBuildsTheSameTwoConditions() {
        Points.Filter f = QdrantSearchService.authFilter(TENANT, LEVEL);
        assertEquals(2, f.getMustCount());

        assertEquals(IdentityFilter.TENANT_FIELD, f.getMust(0).getField().getKey());
        assertEquals(TENANT, f.getMust(0).getField().getMatch().getKeyword());

        assertEquals(IdentityFilter.AUTH_LEVEL_FIELD, f.getMust(1).getField().getKey());
        assertEquals((double) LEVEL, f.getMust(1).getField().getRange().getLte());
    }

    /** 4. 谓词极性：越界/缺失身份一律 fail-closed。 */
    @Test
    void predicateIsFailClosed() {
        assertTrue(IdentityFilter.matches(TENANT, LEVEL, TENANT, 1));
        assertTrue(IdentityFilter.matches(TENANT, LEVEL, TENANT, LEVEL), "边界含等号");
        assertFalse(IdentityFilter.matches(TENANT, LEVEL, "tenant-acme", 1), "跨租户必须拒");
        assertFalse(IdentityFilter.matches(TENANT, LEVEL, TENANT, LEVEL + 1), "高密级文档必须拒");
        assertFalse(IdentityFilter.matches(null, LEVEL, TENANT, 1), "缺 tenant 必须拒（不是放行）");
        assertFalse(IdentityFilter.matches("  ", LEVEL, TENANT, 1), "空 tenant 必须拒");
        assertFalse(IdentityFilter.matches(TENANT, 0, TENANT, 1), "level=0 命中空集");
    }

    /** 5. 三个后端的**同一身份、同一文档**必须给同一个答案（把三份判据并排比一次）。 */
    @Test
    void allThreeBackendsAgreeOnTheSameDocument() {
        record Case(String docTenant, int docLevel, boolean visible) {}
        List<Case> cases = List.of(
                new Case(TENANT, 1, true),
                new Case(TENANT, 3, true),
                new Case(TENANT, 4, false),
                new Case("tenant-acme", 1, false));
        for (Case c : cases) {
            assertEquals(c.visible(), IdentityFilter.matches(TENANT, LEVEL, c.docTenant(), c.docLevel()),
                    "内存谓词与期望不一致: " + c);
        }
        // ES/Qdrant 侧无法对"单条文档"求值（那是引擎的事），但它们构造出的条件里
        // 算子与字段已经在上面的用例里被钉住——谓词是这三者的共同语义说明。
        assertTrue(EsSearchService.identityFilters(TENANT, LEVEL).get(1).range().lte() != null);
        assertTrue(QdrantSearchService.authFilter(TENANT, LEVEL).getMust(1).getField().getRange().getLte()
                > 0);
    }
}
