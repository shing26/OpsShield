package com.opspilot.alert;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.incident.IncidentService;
import com.opspilot.metrics.AuditService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AC2 的边界：告警台账坏掉不能拖垮排障——{@link AlertIntakeService#record} 必须自己吞掉
 * 存储异常（只 warn），而不是把异常抛回 ChatOrchestrator。
 *
 * <p>这里用 mock store 直接注入故障，比启动整条编排链更能把"best-effort"钉死在写入点。
 */
class AlertIntakeBestEffortTest {

    private static OpsPilotProperties props() {
        return new OpsPilotProperties(null, null, null, null, null, null, null, null);
    }

    @Test
    void recordNeverThrowsWhenStorageFails() {
        AlertIntakeStore store = mock(AlertIntakeStore.class);
        doThrow(new RuntimeException("h2 is down"))
                .when(store).touch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any());
        AlertIntakeService service = new AlertIntakeService(store, mock(IncidentService.class),
                mock(AuditService.class), props(), new ObjectMapper());

        assertDoesNotThrow(() -> service.record("t", "alert-1", "trace-1", "摘要", "svc", "prod", null, null),
                "存储异常必须被 record 吞掉，不得让诊断请求失败");
    }

    @Test
    void blankTenantOrAlertIdIsIgnoredWithoutTouchingStorage() {
        AlertIntakeStore store = mock(AlertIntakeStore.class);
        AlertIntakeService service = new AlertIntakeService(store, mock(IncidentService.class),
                mock(AuditService.class), props(), new ObjectMapper());

        service.record(null, "alert-1", null, "摘要", "svc", "prod", null, null);
        service.record("t", " ", null, "摘要", "svc", "prod", null, null);
        verifyNoInteractions(store);
    }
}
