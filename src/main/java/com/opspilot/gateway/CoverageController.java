package com.opspilot.gateway;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.retrieval.CoverageService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 覆盖范围声明端点（ADR-0023 决策 5）：**提问前**就能看到的知识库边界。
 *
 * 三条设计口径：
 * ① **按调用者身份过滤**（tenant × auth_level），过滤条件与检索**同一份**
 *    （{@link com.opspilot.retrieval.EsSearchService#identityFilters}）——清单本身是越权面，
 *    给外部租户列出内部租户的服务名等于泄语料边界。
 * ② **不占配额**：这里是 ES 计数与分面，没有 LLM/embedding 成本。与 `/search`（评测路径豁免配额）
 *    同一判断逻辑——把成本护栏挂在没有成本的地方，只会让人不敢用这个入口。
 * ③ **成功不落审计**：与 `/admin/state` 同款口径（只读面成功不污染证据流）。被拒由 JwtAuthFilter
 *    在入口 401，够不着这里；下面的 `user == null` 只是"绕过 filter 的调用路径"的纵深防御兜底
 *    （与 SearchController / FeedbackController 同款写法，单测直调 controller 时 filter 不参与）。
 */
@RestController
@RequestMapping("/api/v1/copilot")
public class CoverageController {

    private final CoverageService coverage;

    public CoverageController(CoverageService coverage) {
        this.coverage = coverage;
    }

    @GetMapping("/coverage")
    public Map<String, Object> coverage(HttpServletRequest http) throws Exception {
        UserContext user = JwtAuthFilter.from(http);
        if (user == null || user.authLevel() < 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "auth_level 非法（须 >=1）");
        }
        CoverageService.Coverage c = coverage.coverage(user.tenantId(), user.authLevel());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", c.tenant());
        out.put("auth_level", c.authLevel());
        out.put("services", c.services());       // = service_list 长度（单一来源，摘要不会与列表打架）
        out.put("error_codes", c.errorCodes());  // = error_code_list 长度
        out.put("chunks", c.chunks());
        out.put("built_at", c.builtAt());        // epoch ms；取不到就是 null，不编时间
        out.put("facets_truncated", c.facetsTruncated());
        out.put("service_list", c.serviceList().stream().map(f -> facet("service", f)).toList());
        out.put("error_code_list", c.errorCodeList().stream().map(f -> facet("code", f)).toList());
        return out;
    }

    /** 分面项装配。用 LinkedHashMap 而非 Map.of：`Map.of` 对 null 值抛 NPE，
     *  而"服务名为空"这种索引映射异常应该照实传出去，不该变成 500。 */
    private static Map<String, Object> facet(String key, CoverageService.Facet f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, f.name());
        m.put("chunks", f.chunks());
        return m;
    }
}
