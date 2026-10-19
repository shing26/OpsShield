package com.opspilot.storm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** QA 缺陷 #3 回归：真实告警变体（唯一 msgId/traceId/时间戳/耗时）应归一到同一指纹。 */
class FingerprintServiceTest {

    private final FingerprintService svc = new FingerprintService();

    @Test
    void variantsOfSameAlertShareFingerprint() {
        String a = "2026-09-08T03:14:22.113 ERROR msgId=abc123def456 traceId=7f3a9b2c "
                + "SQLTransientException error code 50012_DB_TIMEOUT elapsed=1842ms order=88342911";
        String b = "2026-09-08T03:19:55.777 ERROR msgId=xyz789ghi012 traceId=1a2b3c4d "
                + "SQLTransientException error code 50012_DB_TIMEOUT elapsed=93ms order=11223344";
        assertEquals(svc.fingerprint("order-service", "prod", a),
                     svc.fingerprint("order-service", "prod", b),
                     "同错误码不同实例噪声应收敛到同一指纹");
    }

    @Test
    void differentErrorCodesDiffer() {
        String a = "error code 50012_DB_TIMEOUT at OrderCreateService";
        String b = "error code 50013_DB_DEADLOCK at StockDeductService";
        assertNotEquals(svc.fingerprint("order-service", "prod", a),
                        svc.fingerprint("order-service", "prod", b));
    }

    @Test
    void errorCodePreservedInNormalization() {
        String norm = svc.normalizeError("2026-09-08T03:14:22 error code 50012_DB_TIMEOUT msgId=abc123");
        assertTrue(norm.contains("50012_DB_TIMEOUT"), "错误码必须保留");
        assertFalse(norm.contains("abc123"), "msgId 应被掩码");
    }

    /** P2 边界：错误码数量突破单字母占位符 26 上限后仍须全部完整还原。 */
    @Test
    void moreThanTwentySixCodesAllSurviveNormalization() {
        StringBuilder msg = new StringBuilder("cascade at 2026-09-08T03:14:22: ");
        for (int i = 0; i < 30; i++) {
            msg.append(String.format("5%04d_SYM%d ", i, i));
        }
        String norm = svc.normalizeError(msg.toString());
        for (int i = 0; i < 30; i++) {
            assertTrue(norm.contains(String.format("5%04d_SYM%d", i, i)),
                    "第 " + i + " 个错误码应在占位-掩码-还原后存活");
        }
    }

    /**
     * 指纹是**对外形状契约**：它同时是 L1 缓存键的组分、SSE meta 的回传值、以及反馈端点归档
     * "问题"的键。实现取 SHA-256 摘要的**前 128 位**（32 位小写十六进制）——若有人去掉
     * `substring(0, 32)`，指纹会变成 64 位：不报错、但 L1 键与已归档的反馈全部对不上。
     *
     * 变异验证：去掉 `substring(0, 32)` → 本用例必红（长度断言）；改成大写十六进制 → 亦必红。
     */
    @Test
    void fingerprintShapeIsLockedToThirtyTwoLowercaseHex() {
        String fp = svc.fingerprint("order-service", "prod", "error code 50012_DB_TIMEOUT");
        assertEquals(32, fp.length(), "指纹是 SHA-256 的前 128 位（32 位十六进制），不是 64 位: " + fp);
        assertTrue(fp.matches("[0-9a-f]{32}"), "只允许小写十六进制: " + fp);
        // 同输入必须可重放（指纹是缓存键，长度或取值漂移都会静默打断命中）
        assertEquals(fp, svc.fingerprint("order-service", "prod", "error code 50012_DB_TIMEOUT"));
    }

    /** 分隔符参与摘要：service/env 边界若被拼串吃掉，不同 (service,env) 组合会撞同一指纹。 */
    @Test
    void serviceAndEnvBoundariesAreNotCollapsible() {
        assertNotEquals(svc.fingerprint("a", "bc", "m"), svc.fingerprint("ab", "c", "m"),
                "`|` 分隔符必须参与摘要，否则 service/env 边界可被拼接抹平");
    }

    /** 告警身份形状与文本指纹一致（32 位小写十六进制），否则 SSE meta/审计/反馈形状锁会松。 */
    @Test
    void alertIdentityShapeMatchesFingerprintShape() {
        String id = svc.identity("c817f438192dbb63f17859b36e149f81");
        assertEquals(32, id.length(), "告警身份同为 SHA-256 前 128 位: " + id);
        assertTrue(id.matches("[0-9a-f]{32}"), "只允许小写十六进制: " + id);
        assertEquals(id, svc.identity("c817f438192dbb63f17859b36e149f81"), "身份必须可重放（缓存键组分）");
    }

    /** 身份对小差异敏感：firing/resolved 的隔离由调用方在 alertId 里带上，这里锁住"不同即不同"。 */
    @Test
    void differentAlertIdsDiffer() {
        assertNotEquals(svc.identity("alert-a|firing"), svc.identity("alert-a|resolved"));
    }

    /**
     * 前缀参与摘要的变异锁：{@code service="aaaa", env=""} 的文本指纹输入恰是 {@code "aaaa||bbbb"}。
     * 去掉 {@code "alert-id|"} 前缀，本用例必红。
     *
     * <p>注意：这是**具体构造**的隔离锁，不是"全局不可能撞车"的证明——service/env 是自由文本，
     * 理论上仍存在其他构造（见 {@link FingerprintService#identity(String)} 的扁注）。
     */
    @Test
    void identityPrefixKeepsItDistinctFromTextFingerprint() {
        assertNotEquals(svc.identity("aaaa||bbbb"), svc.fingerprint("aaaa", "", "bbbb"),
                "去掉 alert-id 前缀会让这个构造的两种身份撞车");
    }
}
