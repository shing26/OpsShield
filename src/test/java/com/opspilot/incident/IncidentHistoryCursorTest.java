package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 事故历史游标分页（ADR-0038）。这里钉的是键集分页的四类失败模式：
 * 同毫秒丢行、跨筛选复用游标、非法游标落 500、用游标扩大租户可见范围。
 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:incident_history_cursor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.clean-disabled=true"})
class IncidentHistoryCursorTest {

    @org.springframework.test.context.DynamicPropertySource
    static void jwtSecret(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "incidenthistorycursor".repeat(3));
    }

    static final UserContext ALICE = new UserContext("t1-alice", "sre", 3, "tenant-1");
    static final UserContext BOB = new UserContext("t1-bob", "sre", 3, "tenant-1");
    static final UserContext CAROL = new UserContext("t2-carol", "sre", 3, "tenant-2");

    @Autowired IncidentService service;
    @Autowired IncidentController controller;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private MockMvc mvc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM incident_notifications");
        jdbc.update("DELETE FROM incident_comments");
        jdbc.update("DELETE FROM incident_assignments");
        jdbc.update("DELETE FROM incidents");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void insert(String id, String tenant, String owner, String status, String sla, String updatedAt) {
        Timestamp at = Timestamp.from(Instant.parse(updatedAt));
        jdbc.update("INSERT INTO incidents (incident_id, tenant, client_id, title, query, source, status,"
                        + " owner, created_by, created_at, updated_at, sla_state)"
                        + " VALUES (?, ?, ?, ?, ?, 'manual', ?, ?, ?, ?, ?, ?)",
                id, tenant, "cid-" + id, "标题 " + id, "排查 " + id, status, owner,
                owner == null ? "t1-alice" : owner, at, at, sla);
    }

    private void seedRanksForAlice() {
        // rank0（breached）三条同一 updated_at：毫秒比较会在这里丢行。
        insert("INC-1125-0005", "tenant-1", "t1-alice", "open", "breached", "2026-11-25T10:00:00.123456Z");
        insert("INC-1125-0003", "tenant-1", "t1-alice", "open", "breached", "2026-11-25T10:00:00.123456Z");
        insert("INC-1125-0009", "tenant-1", "t1-alice", "open", "breached", "2026-11-25T10:00:00.123456Z");
        // rank1（due_soon）两条同刻。
        insert("INC-1125-0002", "tenant-1", "t1-alice", "open", "due_soon", "2026-11-25T09:00:00.500000Z");
        insert("INC-1125-0007", "tenant-1", "t1-alice", "open", "due_soon", "2026-11-25T09:00:00.500000Z");
        // rank2：ok / closed（closed 先判，即使 breached 也是 2）。
        insert("INC-1125-0001", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T08:00:00Z");
        insert("INC-1125-0004", "tenant-1", "t1-alice", "closed", "breached", "2026-11-25T11:00:00Z");
        insert("INC-1125-0008", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T12:00:00Z");
        insert("INC-1125-0006", "tenant-1", "t1-alice", "closed", "ok", "2026-11-25T12:00:00Z");
    }

    @Test
    void keysetPagesCoverEveryRowOnceInExactOrder() {
        seedRanksForAlice();
        List<String> expected = List.of(
                "INC-1125-0009", "INC-1125-0005", "INC-1125-0003",
                "INC-1125-0007", "INC-1125-0002",
                "INC-1125-0008", "INC-1125-0006", "INC-1125-0004", "INC-1125-0001");

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            IncidentPage page = service.page(ALICE, null, null, cursor, "2");
            assertTrue(page.incidents().size() <= 2, "每页不得超过 limit");
            page.incidents().forEach(i -> seen.add(i.id()));
            cursor = page.nextCursor();
            if (page.hasMore()) assertNotNull(cursor, "hasMore=true 必须有 nextCursor");
            else assertNull(cursor, "hasMore=false 时 nextCursor 必须为 null");
            assertTrue(++pages < 20, "游标没有前进，可能死循环");
        } while (cursor != null);

        assertEquals(expected, seen, "并集顺序必须与 (sla_rank, updated_at DESC, incident_id DESC) 逐项一致");
        assertEquals(seen.size(), new HashSet<>(seen).size(), "游标分页不得重复行");
    }

    @Test
    void openCountIsWholeTenantEvenWhenPageIsFiltered() {
        insert("INC-1125-0001", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T10:00:00Z");
        insert("INC-1125-0002", "tenant-1", "t1-bob", "open", "ok", "2026-11-25T09:00:00Z");
        insert("INC-1125-0003", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T08:00:00Z");
        insert("INC-1125-0004", "tenant-1", "t1-alice", "closed", "ok", "2026-11-25T07:00:00Z");
        insert("INC-1125-0005", "tenant-1", "t1-alice", "closed", "ok", "2026-11-25T06:00:00Z");

        IncidentPage first = service.page(ALICE, null, "closed", null, "1");
        assertEquals(3, first.openCount(), "openCount 不受 status=closed 影响");
        assertTrue(first.hasMore());
        IncidentPage second = service.page(ALICE, null, "closed", first.nextCursor(), "1");
        assertEquals(3, second.openCount(), "openCount 不受游标与页码影响");
    }

    @Test
    void cursorIsBoundToMineAndStatus() {
        insert("INC-1125-0001", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T10:00:00Z");
        insert("INC-1125-0002", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T09:00:00Z");
        insert("INC-1125-0003", "tenant-1", "t1-alice", "closed", "ok", "2026-11-25T08:00:00Z");

        String mineCursor = service.page(ALICE, "true", null, null, "1").nextCursor();
        assertNotNull(mineCursor);
        ResponseStatusException mineMismatch = assertThrows(ResponseStatusException.class,
                () -> service.page(ALICE, "false", null, mineCursor, "1"));
        assertEquals(HttpStatus.BAD_REQUEST, mineMismatch.getStatusCode());
        assertTrue(String.valueOf(mineMismatch.getReason()).contains("游标与筛选条件不匹配"));
        assertDoesNotThrow(() -> service.page(ALICE, "true", null, mineCursor, "1"));

        String openCursor = service.page(ALICE, null, "open", null, "1").nextCursor();
        assertNotNull(openCursor);
        ResponseStatusException statusMismatch = assertThrows(ResponseStatusException.class,
                () -> service.page(ALICE, null, "closed", openCursor, "1"));
        assertEquals(HttpStatus.BAD_REQUEST, statusMismatch.getStatusCode());
        assertTrue(String.valueOf(statusMismatch.getReason()).contains("游标与筛选条件不匹配"));
        assertDoesNotThrow(() -> service.page(ALICE, null, "open", openCursor, "1"));
    }

    @Test
    void invalidCursorsAreBadRequestNotServerError() {
        insert("INC-1125-0001", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T10:00:00Z");
        insert("INC-1125-0002", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T09:00:00Z");
        String valid = service.page(ALICE, null, null, null, "1").nextCursor();
        assertNotNull(valid, "至少两行才会产生游标；本测试只验证非法输入");
        List<String> bad = List.of(
                "!!!",
                b64("v2|0|1000|INC-1125-0001||"),
                b64("v1|3|1000|INC-1125-0001||"),
                b64("v1|0|1000|||"),
                b64("v1|0|1000|BAD-ID||"),
                b64("v1|0|1000|INC-1125-0001||").substring(0, 5));
        for (String cursor : bad) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.page(ALICE, null, null, cursor, "2"), "应拒绝: " + cursor);
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode(), "非法游标必须 400 而不是 500");
            assertTrue(String.valueOf(e.getReason()).contains("游标不合法"));
        }
    }

    @Test
    void limitDefaultsToSixtyAndRejectsOutOfRange() {
        for (int i = 1; i <= 105; i++) {
            insert(String.format("INC-1125-%04d", i), "tenant-1", "t1-alice", "open", "ok",
                    "2026-11-25T10:00:00Z");
        }
        assertEquals(60, service.page(ALICE, null, null, null, null).incidents().size(), "缺省 limit=60");
        assertEquals(1, service.page(ALICE, null, null, null, "1").incidents().size());
        assertEquals(100, service.page(ALICE, null, null, null, "100").incidents().size());
        for (String limit : List.of("0", "101", "abc", "", "-1", "1.5")) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.page(ALICE, null, null, null, limit), "应拒绝 limit=" + limit);
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }
    }

    @Test
    void tenantBCanReuseTenantACursorAndOnlySeesTenantBRows() {
        insert("INC-1125-0001", "tenant-1", "t1-alice", "open", "breached", "2026-11-25T12:00:00Z");
        insert("INC-1125-0002", "tenant-1", "t1-alice", "open", "breached", "2026-11-25T11:00:00Z");
        insert("INC-1125-0003", "tenant-2", "t2-carol", "open", "ok", "2026-11-25T01:00:00Z");
        insert("INC-1125-0004", "tenant-2", "t2-carol", "open", "ok", "2026-11-25T00:00:00Z");

        String aCursor = service.page(ALICE, null, null, null, "1").nextCursor();
        assertNotNull(aCursor);
        IncidentPage bPage = service.page(CAROL, null, null, aCursor, "10");
        assertEquals(List.of("INC-1125-0003", "INC-1125-0004"),
                bPage.incidents().stream().map(Incident::id).toList(),
                "游标不能扩大可见范围：B 只看到自己的行");
        assertTrue(bPage.incidents().stream().allMatch(i -> "tenant-2".equals(i.tenant())));
    }

    @Test
    void endpointReturnsCursorFieldsAndKeepsExistingRowShape() throws Exception {
        insert("INC-1125-0001", "tenant-1", "t1-alice", "open", "breached", "2026-11-25T10:00:00Z");
        insert("INC-1125-0002", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T09:00:00Z");
        insert("INC-1125-0003", "tenant-1", "t1-alice", "closed", "ok", "2026-11-25T08:00:00Z");

        MvcResult first = mvc.perform(get("/api/v1/copilot/incidents")
                        .param("limit", "2")
                        .requestAttr(UserContext.REQUEST_ATTR, ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidents.length()").value(2))
                .andExpect(jsonPath("$.incidents[0].id").value("INC-1125-0001"))
                .andExpect(jsonPath("$.incidents[0].title").exists())
                .andExpect(jsonPath("$.incidents[0].updatedAt").exists())
                .andExpect(jsonPath("$.incidents[0].slaState").value("breached"))
                .andExpect(jsonPath("$.incidents[0].lastCommentId").value(0))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.openCount").value(2))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        JsonNode body = json.readTree(first.getResponse().getContentAsString());
        String cursor = body.get("nextCursor").asText();

        MvcResult second = mvc.perform(get("/api/v1/copilot/incidents")
                        .param("limit", "2")
                        .param("cursor", cursor)
                        .requestAttr(UserContext.REQUEST_ATTR, ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidents.length()").value(1))
                .andExpect(jsonPath("$.incidents[0].id").value("INC-1125-0003"))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn();
        assertTrue(json.readTree(second.getResponse().getContentAsString()).get("nextCursor").isNull(),
                "最后一页 nextCursor 必须是 null");
    }

    @Test
    void endpointRejectsCursorFilterMismatchWith400() throws Exception {
        insert("INC-1125-0001", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T10:00:00Z");
        insert("INC-1125-0002", "tenant-1", "t1-alice", "open", "ok", "2026-11-25T09:00:00Z");
        MvcResult first = mvc.perform(get("/api/v1/copilot/incidents")
                        .param("status", "open")
                        .param("limit", "1")
                        .requestAttr(UserContext.REQUEST_ATTR, ALICE))
                .andExpect(status().isOk())
                .andReturn();
        String cursor = json.readTree(first.getResponse().getContentAsString()).get("nextCursor").asText();

        mvc.perform(get("/api/v1/copilot/incidents")
                        .param("status", "closed")
                        .param("cursor", cursor)
                        .requestAttr(UserContext.REQUEST_ATTR, ALICE))
                .andExpect(status().isBadRequest());
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
