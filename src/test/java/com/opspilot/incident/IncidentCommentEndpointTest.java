package com.opspilot.incident;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不可变端点锁（ADR-0034）：评论只有 GET / POST，没有编辑/删除。
 *
 * <p>为什么值得单独测：Spring 对「路径存在但方法不支持」默认报 405，但一旦有人加一个
 * 兜底映射（或误加 {@code @DeleteMapping}），删除就会变成静默成功——而删除是契约的
 * forbidden 项。这里把 405 钉在路由层，而不是靠「我们没写」这句话。
 */
class IncidentCommentEndpointTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new IncidentController(mock(IncidentService.class), mock(ObjectMapper.class)))
            .build();

    @Test
    void deleteCommentsIsMethodNotAllowedNotSilentSuccess() throws Exception {
        mvc.perform(delete("/api/v1/copilot/incidents/INC-1/comments"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void editingCommentsIsAlsoNotExposed() throws Exception {
        mvc.perform(patch("/api/v1/copilot/incidents/INC-1/comments"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void getAndPostOnTheSamePathAreMapped() throws Exception {
        // 反证：405 是「方法不支持」，不是「路径不存在」——GET 必须能进入 handler
        // （无身份属性时 Controller 返回 403，说明路由已命中；DELETE 的 405 因此不是假阳性）。
        mvc.perform(get("/api/v1/copilot/incidents/INC-1/comments"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/copilot/incidents/INC-1/comments"))
                .andExpect(status().isForbidden());
    }
}
