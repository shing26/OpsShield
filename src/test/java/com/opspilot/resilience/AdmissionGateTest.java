package com.opspilot.resilience;

import com.opspilot.config.AdmissionProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1 准入回归锁（2026-10-22 P0-5）：全局与租户两级许可都必须生效，且释放幂等。
 * 生产后果由 48 并发唯一指纹实测背书：没有舱壁时 3 秒内 L1→L2，所有租户只拿静态 SOP。
 */
class AdmissionGateTest {

    @Test
    void globalPermitCapsTotalAcrossTenants() {
        AdmissionGate gate = new AdmissionGate(new AdmissionProperties(2, 2, 1));
        AdmissionGate.Permit a = gate.tryAcquire("t-a");
        AdmissionGate.Permit b = gate.tryAcquire("t-b");
        assertNotNull(a);
        assertNotNull(b);
        assertNull(gate.tryAcquire("t-c"), "全局 2 个许可耗尽后必须拒绝，不能让第三路穿透");
        assertEquals(0, gate.availableGlobalPermits());
    }

    @Test
    void tenantPermitCapsSingleTenantEvenWhenGlobalFree() {
        AdmissionGate gate = new AdmissionGate(new AdmissionProperties(8, 1, 1));
        AdmissionGate.Permit first = gate.tryAcquire("t-a");
        assertNotNull(first);
        assertNull(gate.tryAcquire("t-a"), "同租户第 2 路必须拒绝，否则单人仍可占满全局");
        assertEquals(7, gate.availableGlobalPermits(), "拒绝租户级许可时必须归还全局许可，不能泄漏");
        assertNotNull(gate.tryAcquire("t-b"), "其他租户不受影响（隔离的目标）");
    }

    @Test
    void releaseRestoresBothLevelsAndIsIdempotent() {
        AdmissionGate gate = new AdmissionGate(new AdmissionProperties(1, 1, 1));
        AdmissionGate.Permit p = gate.tryAcquire("t-a");
        assertNotNull(p);
        assertNull(gate.tryAcquire("t-b"));
        p.release();
        p.release();   // 重复释放不得把信号量放大
        assertEquals(1, gate.availableGlobalPermits());
        assertEquals(1, gate.availableTenantPermits("t-a"));
        assertNotNull(gate.tryAcquire("t-b"));
    }

    @Test
    void blankTenantFallsBackToUnknownBucket() {
        AdmissionGate gate = new AdmissionGate(new AdmissionProperties(2, 1, 1));
        assertNotNull(gate.tryAcquire(null));
        assertNull(gate.tryAcquire(" "), "null/空白租户归 unknown；第 2 路仍受租户上限约束");
    }
}
