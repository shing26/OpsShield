package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.retrieval.HybridSearchService;
import com.opspilot.retrieval.SearchOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P0 纵深防御锁：即便绕过 JwtAuthFilter 直抵 Controller，auth_level<1 也不得触发检索。 */
class SearchControllerTest {

    private final HybridSearchService svc = mock(HybridSearchService.class);
    private final com.opspilot.metrics.AuditService audit = mock(com.opspilot.metrics.AuditService.class);
    private final SearchController controller = new SearchController(svc, audit);

    private static MockHttpServletRequest withUser(int level) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.REQUEST_ATTR, new UserContext("u", "sre", level, "tenant-demo"));
        return req;
    }

    @Test
    void zeroAndNegativeLevelsAreForbiddenWithoutTouchingSearch() {
        for (int level : new int[]{0, -1}) {
            var ex = assertThrows(ResponseStatusException.class,
                    () -> controller.search(new SearchController.SearchReq("q", "hybrid"), withUser(level)),
                    "level=" + level + " 必须被 Controller 兜底拒绝");
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }
        verifyNoInteractions(svc);
    }

    /** #18：检索面同样有 query 上限（embedding/rerank 成本护栏），超限不得进腿。 */
    @Test
    void oversizeQueryIsRejectedBeforeSearch() {
        var ex = assertThrows(ResponseStatusException.class, () -> controller.search(
                new SearchController.SearchReq("x".repeat(8193), "hybrid"), withUser(1)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(svc);
    }

    @Test
    void validLevelReachesSearchService() {
        when(svc.search(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new SearchOutcome(List.of(), "hybrid", false, false, 1.0, 1,
                        new com.opspilot.retrieval.LegTimings(1, 2, 3, 4)));
        var resp = controller.search(new SearchController.SearchReq("q", "hybrid"), withUser(1));
        assertEquals("hybrid", resp.get("mode"));
        verify(svc).search("q", "tenant-demo", 1, "hybrid");
    }
}
