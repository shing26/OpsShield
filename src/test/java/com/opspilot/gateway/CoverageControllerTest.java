package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.retrieval.CoverageService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 覆盖端点的三态锁（口径与 SearchController / FeedbackController 同款）：
 * 无身份必须 403、身份只认 token（客户端不能指定 tenant/level）、线形状必须是显式 snake_case。
 *
 * 第三条值得锁的理由：`built_at` 允许为 null，而 `Map.of` 对 null 值抛 NPE——用错装配方式会让
 * "ES 取不到索引创建时刻"这条**正常**分支变成 500。这不是风格问题，是可用性问题。
 */
class CoverageControllerTest {

    private final CoverageService svc = mock(CoverageService.class);
    private final CoverageController controller = new CoverageController(svc);

    private static MockHttpServletRequest withUser(String tenant, int level) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.REQUEST_ATTR, new UserContext("u", "sre", level, tenant));
        return req;
    }

    private static CoverageService.Coverage sample() {
        return new CoverageService.Coverage("tenant-acme", 3, 12, null,
                List.of(new CoverageService.Facet("order-service", 7),
                        new CoverageService.Facet("pay-service", 5)),
                List.of(new CoverageService.Facet("50012_DB_TIMEOUT", 4)),
                false);
    }

    /** 无身份属性 = filter 未参与（绕过路径）：必须 403，且不得去查语料。 */
    @Test
    void unauthenticatedIsForbiddenWithoutTouchingTheCorpus() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.coverage(new MockHttpServletRequest()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(svc);
    }

    @Test
    void zeroAndNegativeLevelsAreForbidden() {
        for (int level : new int[]{0, -1}) {
            var ex = assertThrows(ResponseStatusException.class,
                    () -> controller.coverage(withUser("tenant-acme", level)),
                    "level=" + level + " 必须被 Controller 兜底拒绝");
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }
        verifyNoInteractions(svc);
    }

    /** 身份只从 token 来：tenant 与 level 原样透传给服务层，客户端没有覆盖口。 */
    @Test
    void identityComesFromTheToken() throws Exception {
        when(svc.coverage("tenant-acme", 2)).thenReturn(sample());
        controller.coverage(withUser("tenant-acme", 2));
        verify(svc).coverage("tenant-acme", 2);
    }

    /** 线形状：显式 snake_case 键；计数等于列表长度；built_at 为 null 也必须正常返回。 */
    @Test
    void wireShapeIsExplicitSnakeCaseAndToleratesNullBuiltAt() throws Exception {
        when(svc.coverage(anyString(), anyInt())).thenReturn(sample());
        Map<String, Object> out = controller.coverage(withUser("tenant-acme", 3));

        assertEquals("tenant-acme", out.get("tenant"));
        assertEquals(3, out.get("auth_level"));
        assertEquals(2L, out.get("services"));
        assertEquals(1L, out.get("error_codes"));
        assertEquals(12L, out.get("chunks"));
        assertNull(out.get("built_at"));
        assertEquals(Boolean.FALSE, out.get("facets_truncated"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) out.get("service_list");
        assertEquals("order-service", services.get(0).get("service"));
        assertEquals(7L, services.get(0).get("chunks"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> codes = (List<Map<String, Object>>) out.get("error_code_list");
        assertEquals("50012_DB_TIMEOUT", codes.get(0).get("code"));
    }
}
