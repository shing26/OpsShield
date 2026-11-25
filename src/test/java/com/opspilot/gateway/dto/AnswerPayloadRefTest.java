package com.opspilot.gateway.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Ref 的兼容与归一锁（AC6/AC8）：旧缓存缺字段必须回落 unknown，非法值不得透传。 */
class AnswerPayloadRefTest {

    @Test
    void legacyJsonWithoutNewFieldsDeserializesToUnknown() throws Exception {
        String json = "{\"answer\":\"a\",\"refs\":[{\"chunkId\":\"rb-001::x\",\"breadcrumb\":\"b\","
                + "\"service\":\"s\"}],\"actions\":[],\"mode\":\"hybrid\",\"fastPath\":false,"
                + "\"maxAuthLevel\":3,\"tenant\":\"t\",\"refused\":false,\"nearMiss\":[]}";
        AnswerPayload p = new ObjectMapper().readValue(json, AnswerPayload.class);
        AnswerPayload.Ref r = p.refs().get(0);
        assertNull(r.docId(), "旧缓存没有文档身份");
        assertNull(r.docDigest(), "旧缓存没有版本身份");
        assertNull(r.reviewedAt());
        assertEquals("unknown", r.staleness(), "缺失必须回落 unknown，绝不能默认 fresh");
    }

    @Test
    void garbageStalenessNormalizesToUnknown() {
        AnswerPayload.Ref r = new AnswerPayload.Ref("c", "b", "s", "d", "a1b2c3d4e5f6",
                "2026-09-16", "expired");
        assertEquals("unknown", r.staleness());
    }

    @Test
    void validStalenessIsPreserved() {
        for (String s : new String[]{"fresh", "stale", "unknown"}) {
            assertEquals(s, new AnswerPayload.Ref("c", "b", "s", "d", "a1b2c3d4e5f6", null, s).staleness());
        }
    }

    @Test
    void newFieldsRoundTripThroughCacheJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AnswerPayload.Ref before = new AnswerPayload.Ref("c", "b", "s", "rb-001",
                "a1b2c3d4e5f6", "2026-09-16", "stale");
        AnswerPayload.Ref after = mapper.readValue(mapper.writeValueAsString(before), AnswerPayload.Ref.class);
        assertEquals(before, after);
    }
}
