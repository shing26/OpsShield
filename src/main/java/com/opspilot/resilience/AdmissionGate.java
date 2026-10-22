package com.opspilot.resilience;

import com.opspilot.config.AdmissionProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 两级并发准入（G1，2026-10-22 运维审查 P0-5）。
 *
 * <p>此前全仓没有任何并发准入：SingleFlight 只折叠**同指纹**，于是 45 条不同指纹的告警/脚本循环
 * 可以同时打满上游并发流；上游 429 三次即把全局降级打到 L2，**所有租户**一起拿静态 SOP。
 * 这不是"容量不足"，是"没有舱壁"——一个来源的流量能改变所有人的答案质量。
 *
 * <p>设计取舍（ADR-0026）：
 * <ul>
 *   <li><b>立即失败而非排队</b>：拿不到许可就 429 + Retry-After。SSE 的首字节已经够贵，
 *       排队只会把超时从上游挪到网关，且会把在途数顶到降级线；未来若要"短暂等待"，在
 *       {@link #tryAcquire} 里加有界 park 即可，不影响调用方契约。</li>
 *   <li><b>全局 + 每租户</b>：全局保上游配额，租户保公平。租户键来自 DB 签发的 JWT 主体，
 *       不是调用方自由文本；租户数有界（当前 3 个）。</li>
 *   <li><b>不放宽降级状态机</b>：降级仍是全局的（ADR-0026 决策 4）；准入负责**不让单人**
 *       把全局状态打亮，而不是把状态拆成 N 份。</li>
 * </ul>
 *
 * <p>许可必须在编排 finally 里释放（{@link ChatOrchestrator}），异常/客户端断连也不能漏；
 * {@link Permit#release()} 幂等，重复释放不会把信号量放大。
 */
@Component
public class AdmissionGate {

    private final Semaphore global;
    private final int tenantPermits;
    private final int retryAfterSeconds;
    private final ConcurrentHashMap<String, Semaphore> tenants = new ConcurrentHashMap<>();

    public AdmissionGate(AdmissionProperties props) {
        this.global = new Semaphore(props.globalPermits(), true);
        this.tenantPermits = props.tenantPermits();
        this.retryAfterSeconds = props.retryAfterSeconds();
    }

    /**
     * 非阻塞获取许可。返回 null = 拒绝（调用方回 429 + Retry-After）。
     * tenantId 为空时归到 "unknown"（防御：理论上 JWT 校验已拒绝空租户）。
     */
    public Permit tryAcquire(String tenantId) {
        String key = tenantId == null || tenantId.isBlank() ? "unknown" : tenantId;
        if (!global.tryAcquire()) {
            return null;
        }
        Semaphore tenant = tenants.computeIfAbsent(key, k -> new Semaphore(tenantPermits, true));
        if (!tenant.tryAcquire()) {
            global.release();
            return null;
        }
        return new Permit(tenant);
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 测试/排障只读：当前全局剩余许可。 */
    public int availableGlobalPermits() {
        return global.availablePermits();
    }

    /** 租户剩余许可；不存在时返回 tenantPermits（尚未分配）。 */
    public int availableTenantPermits(String tenantId) {
        Semaphore s = tenants.get(tenantId);
        return s == null ? tenantPermits : s.availablePermits();
    }

    /** 一次准入的凭证；必须有且只释放一次（幂等）。 */
    public final class Permit {
        private final Semaphore tenant;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(Semaphore tenant) {
            this.tenant = tenant;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                tenant.release();
                global.release();
            }
        }
    }
}
