package com.opspilot.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G0 备份可观测面的回归锁。
 * 为什么这些断言值得写：备份是唯一不可再生数据（账号库）的兜底，而"状态文件缺失/损坏时
 * 静默给 0 还是抛异常"这种边界正是值班最容易被骗的地方——缺失必须变成可告警的 0，
 * 而不是把 /actuator/prometheus 拖垮或让应用起不来。
 */
class BackupStatusServiceTest {

    @TempDir
    Path tmp;

    private Path statusFile() {
        return tmp.resolve("last-success.json");
    }

    @Test
    void readsEpochVerifiedAndAgeFromStatusFile() throws Exception {
        long epoch = System.currentTimeMillis() / 1000L - 7200L;   // 2 小时前
        Files.writeString(statusFile(), "{\"epoch\":" + epoch
                + ",\"ts\":\"2026-10-22T00:00:00Z\",\"sha256\":\"ab\",\"size\":123,\"verified\":true}");

        BackupStatusService svc = new BackupStatusService(statusFile());
        assertEquals(epoch, svc.lastSuccessEpoch());
        assertTrue(svc.verified());
        assertEquals(2.0, svc.ageHours(), 0.1, "备份龄 = now - epoch，容差覆盖测试执行耗时");
    }

    @Test
    void missingStatusFileIsUnknownNotFailure() {
        BackupStatusService svc = new BackupStatusService(tmp.resolve("nope.json"));
        var s = svc.snapshot();
        assertEquals(0L, s.lastSuccessEpoch(), "无记录时 gauge 给 0（备份龄会巨大→告警），不能隐藏序列");
        assertEquals(-1.0, s.ageHours(), "面板用 -1 区分『从没备份过』与『刚备份过』");
        assertFalse(s.verified());
    }

    @Test
    void corruptStatusFileDegradesToUnknown() throws Exception {
        Files.writeString(statusFile(), "{ this is not json");
        var s = new BackupStatusService(statusFile()).snapshot();
        assertEquals(BackupStatusService.UNKNOWN, s, "状态文件损坏不得抛异常（否则抓取面被拖垮）");
    }

    @Test
    void registersGaugeWithEpochSeconds() throws Exception {
        long epoch = 1_760_000_000L;
        Files.writeString(statusFile(), "{\"epoch\":" + epoch + ",\"verified\":false}");
        BackupStatusService svc = new BackupStatusService(statusFile());
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        svc.bindTo(reg);

        var gauge = reg.get(BackupStatusService.METRIC_LAST_SUCCESS).gauge();
        assertNotNull(gauge, "指标名是告警规则与面板的共同口径，改名必须同步改规则");
        assertEquals((double) epoch, gauge.value(), 0.0);
    }

    @Test
    void sha256MatchesKnownDigest() throws Exception {
        Path f = tmp.resolve("abc.txt");
        Files.writeString(f, "abc", StandardCharsets.UTF_8);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                BackupStatusService.sha256(f));
    }

    @Test
    void zipIntactDetectsRealAndBrokenArtifacts() throws Exception {
        Path zip = tmp.resolve("users.zip");
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("users.mv.db"));
            zos.write("not really a db, but a readable entry".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertTrue(BackupStatusService.zipIntact(zip), "能完整解出的 zip 才算 verified");

        Path broken = tmp.resolve("broken.zip");
        Files.writeString(broken, "this is not a zip");
        assertFalse(BackupStatusService.zipIntact(broken));
        assertFalse(BackupStatusService.zipIntact(tmp.resolve("missing.zip")));
    }
}
