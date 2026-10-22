package com.opspilot.health;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** G1：依赖健康/磁盘余量必须有 Prometheus 生产者，否则 Redis/磁盘告警永远不 fire。 */
class DependencyMetricsTest {

    @Test
    void refreshExportsDependencyAndDiskGauges() {
        HealthProbe probe = mock(HealthProbe.class);
        when(probe.redis()).thenReturn(new HealthProbe.Comp("UP", "reachable", null));
        when(probe.es()).thenReturn(new HealthProbe.Comp("UP", "docs", 1L));
        when(probe.qdrant()).thenReturn(new HealthProbe.Comp("UP", "pts", 1L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        DependencyMetrics metrics = new DependencyMetrics(probe, registry);
        metrics.register();

        assertEquals(1.0, registry.get("aiops.dependency.up").tag("dependency", "redis").gauge().value());
        assertTrue(registry.get("aiops.disk.free.bytes").tag("path", "data").gauge().value() > 0);

        when(probe.redis()).thenReturn(new HealthProbe.Comp("DOWN", "refused", null));
        metrics.refresh();
        assertEquals(0.0, registry.get("aiops.dependency.up").tag("dependency", "redis").gauge().value());
    }
}
