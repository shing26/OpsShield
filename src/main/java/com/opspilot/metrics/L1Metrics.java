package com.opspilot.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * L1 受控恢复动作的 Prometheus 指标面（2026-10-25 评裁决·盲点 4 的告警基础）。
 *
 * <p>口径：全部 Prometheus-only（不进 /state 面板契约——键集已锁死，见 check_panel_contract.sh）。
 * 基数控制：tenant × 少量闭集（result/phase）——不做 op_id/命令级标签。
 *
 * <p>指标名（Micrometer → Prometheus 输出）：
 * <ul>
 *   <li>aiops_l1_proposed_total{tenant}</li>
 *   <li>aiops_l1_confirmed_total{tenant}</li>
 *   <li>aiops_l1_dispatched_total{tenant,result=ok|failed}</li>
 *   <li>aiops_l1_executed_total{tenant} / aiops_l1_failed_total{tenant}</li>
 *   <li>aiops_l1_expired_total{tenant,phase=confirm|dispatch}</li>
 *   <li>aiops_l1_callback_conflict_total{tenant} / aiops_l1_frozen_total{tenant}</li>
 * </ul>
 */
@Component
public class L1Metrics {

    private final MeterRegistry registry;

    public L1Metrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private void inc(String name, String tenant, String... kv) {
        var b = Counter.builder(name).tag("tenant", tenant == null ? "" : truncate(tenant));
        if (kv.length == 2) {
            b = b.tag(kv[0], kv[1]);
        }
        b.register(registry).increment();
    }

    private static String truncate(String s) {
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    public void proposed(String tenant) { inc("aiops.l1.proposed", tenant); }

    public void confirmed(String tenant) { inc("aiops.l1.confirmed", tenant); }

    public void dispatched(String tenant, boolean ok) {
        inc("aiops.l1.dispatched", tenant, "result", ok ? "ok" : "failed");
    }

    public void executed(String tenant) { inc("aiops.l1.executed", tenant); }

    public void failed(String tenant) { inc("aiops.l1.failed", tenant); }

    public void expired(String tenant, String phase) {
        inc("aiops.l1.expired", tenant, "phase", phase);
    }

    public void callbackConflict(String tenant) { inc("aiops.l1.callback_conflict", tenant); }

    public void frozen(String tenant) { inc("aiops.l1.frozen", tenant); }
}
