package com.opspilot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 最近一次成功备份的"可观测面"（G0，2026-10-22）：读 {@code backup/.last-success.json}，
 * 暴露 {@code aiops.backup.last_success.timestamp.seconds} gauge，并给 {@code /admin/state}
 * 提供 {@code runtime.backup{last_success,age_hours,verified}}。
 *
 * <p><b>为什么需要它</b>：旧备份链路"脚本退出码 0"就是全部信号，而"备份文件在、但从没成功解压过"
 * 这类故障只能靠人某天打开目录才发现。备份的可信度必须变成**机器可读的数字**：
 * 告警规则算"现在 - 最近成功时间"就得到备份龄，面板直接显示同一口径，不再两处各编一套。
 *
 * <p><b>为什么缺失/损坏时给 0 而不是隐藏指标</b>：0 是"从未有过成功备份"这个真实状态，
 * 备份龄会算成巨大值并触发告警——这是想要的失败方向。反过来，如果这里静默消失，
 * 告警规则会因序列不存在而变成"无数据"，值班更容易忽略。
 *
 * <p>实现走 {@link MeterBinder}（同 {@code DegradationStateMetric}）：注册权归指标侧，
 * 本类不依赖 MeterRegistry 构造参数，测试里可以直接 {@code bindTo(new SimpleMeterRegistry())}。
 * 每次读数都重新读文件（几 KB、日更一次），不做缓存——多一个缓存就多一个"缓存与真相不一致"的面。
 */
@Component
public class BackupStatusService implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(BackupStatusService.class);

    /** backup.sh 在全部产物校验通过后才原子写入（tmp + mv），读侧不会看到半截 JSON。 */
    public static final String DEFAULT_STATUS_FILE = "backup/.last-success.json";

    /** 指标名（与 alert 规则、面板同口径的唯一事实源）。 */
    public static final String METRIC_LAST_SUCCESS = "aiops.backup.last_success.timestamp.seconds";

    /** "没有可用记录"的哨兵：ageHours=-1 让面板能区分"从没备份过"和"刚备份过"。 */
    public static final Status UNKNOWN = new Status(0L, -1.0, false);

    private final Path statusFile;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Spring 装配用：默认读仓库根下的 backup/.last-success.json（与 backup.sh 的相对路径一致）。 */
    public BackupStatusService() {
        this(Path.of(DEFAULT_STATUS_FILE));
    }

    /** 测试用：指向临时状态文件，避免依赖工作目录里的真实备份。 */
    BackupStatusService(Path statusFile) {
        this.statusFile = statusFile;
    }

    /** 一次快照：epoch 秒（0=无记录）、备份龄小时（-1=无记录）、是否已校验（unzip -t 通过）。 */
    public record Status(long lastSuccessEpoch, double ageHours, boolean verified) {}

    /** 读状态文件；文件缺失/JSON 损坏都退化为 {@link #UNKNOWN}，绝不因"没有备份"让应用起不来。 */
    public Status snapshot() {
        try {
            if (!Files.isRegularFile(statusFile)) {
                return UNKNOWN;
            }
            JsonNode n = mapper.readTree(Files.readString(statusFile));
            long epoch = n.path("epoch").asLong(0L);
            boolean verified = n.path("verified").asBoolean(false);
            if (epoch <= 0L) {
                return new Status(0L, -1.0, verified);
            }
            double age = Math.max(0.0, (System.currentTimeMillis() / 1000.0 - epoch) / 3600.0);
            return new Status(epoch, age, verified);
        } catch (Exception e) {
            // 只在 debug 留痕：/actuator/prometheus 每 15s 抓一次，warn 会把日志刷满；
            // 真相由 gauge=0 与告警规则承担（"没有成功记录"本身就是告警条件）。
            log.debug("备份状态文件不可读（按未知处理）: {} ({})", statusFile, e.toString());
            return UNKNOWN;
        }
    }

    public long lastSuccessEpoch() {
        return snapshot().lastSuccessEpoch();
    }

    public double ageHours() {
        return snapshot().ageHours();
    }

    public boolean verified() {
        return snapshot().verified();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(METRIC_LAST_SUCCESS, this, s -> (double) s.lastSuccessEpoch())
                .description("最近一次成功备份的 epoch 秒（0=无成功记录；备份龄=time()-该值）")
                .register(registry);
    }

    /**
     * 产物 sha256（小写十六进制）。
     * 为什么网关侧也要算：{@code POST /admin/backup} 的响应若只说"写好了"，
     * 调用方无法确认自己拿到的是哪一份字节；给摘要才能与 backup.sh 落盘的状态对账。
     */
    public static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * zip 完整性：能打开、至少一个条目、每个条目都能完整解出（CRC 校验随读取发生）。
     * 这是 {@code unzip -t} 的进程内等价物——网关不假设宿主有 unzip。
     * H2 备份 zip 只有一两个条目，全量读一遍的成本可以忽略。
     */
    public static boolean zipIntact(Path file) {
        try (ZipFile zf = new ZipFile(file.toFile())) {
            boolean any = false;
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                any = true;
                try (InputStream in = zf.getInputStream(e)) {
                    in.transferTo(java.io.OutputStream.nullOutputStream());
                }
            }
            return any;
        } catch (Exception e) {
            return false;
        }
    }
}
