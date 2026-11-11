package com.opspilot.resilience;

import com.opspilot.store.KvStore;
import com.opspilot.store.MemoryKvStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N-11：SOP 兜底不得"任意取两篇"。
 *
 * <p>旧行为在无命中时会 {@code mapValues(...).limit(2)}，而 {@link KvStore#mapValues} 的顺序
 * 契约明写**不承诺**——返回哪两篇不可复现，对排障者不是帮助而是误导。现在无命中返回 null，
 * 由 {@code ChatOrchestrator} 既有的 {@code sop == null} 分支给固定兜底文案（该类零改动）。
 *
 * <p>三向：①错误码命中 → 返回该 SOP；②无命中但 steps 非空 → null（旧行为会返回任意两篇）；
 * ③steps 为空 → null（既有语义不回归）。
 */
class SopFallbackServiceTest {

    private static final String TENANT = "tenant-sop";

    private static KvStore storeWith(String steps) {
        KvStore kv = new MemoryKvStore();
        for (String entry : steps.split(";")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.split("=", 2);       // docId=text
            kv.mapPut("sop:" + TENANT + ":steps", parts[0], parts[1]);
        }
        return kv;
    }

    @Test
    void errorCodeHitReturnsThatSop() {
        KvStore kv = storeWith("doc-a=重启 inventory-api 的步骤;doc-b=清理缓存的步骤");
        kv.setAdd("sop:" + TENANT + ":code:50012_DB_TIMEOUT", "doc-a");

        String out = new SopFallbackService(kv).lookup(
                "50012_DB_TIMEOUT 怎么处理", null, TENANT);

        assertNotNull(out, "错误码命中应返回对应 SOP");
        assertTrue(out.contains("重启 inventory-api 的步骤"), "应返回命中的那篇：" + out);
        assertFalse(out.contains("清理缓存的步骤"), "不得混入未命中的 SOP：" + out);
    }

    @Test
    void noHitWithNonEmptyStepsReturnsNull() {
        KvStore kv = storeWith("doc-a=重启 inventory-api 的步骤;doc-b=清理缓存的步骤");

        String out = new SopFallbackService(kv).lookup(
                "毫无关联的问题", "unknown-service", TENANT);

        assertNull(out, "无命中时不得返回任意两篇（N-11 删除的正是这段兜底）");
    }

    @Test
    void emptyStepsReturnsNull() {
        KvStore kv = new MemoryKvStore();

        assertNull(new SopFallbackService(kv).lookup("50012_DB_TIMEOUT", null, TENANT),
                "steps 为空必须返回 null（既有语义不回归）");
    }
}
