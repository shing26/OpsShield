-- 告警台账（2026-11-22，ADR-0035；契约 plans/alert-intake-panel-accept.md）。
-- 设计约束：
--   ① 主键是 (tenant, alert_id)，**不是 alert_id**：alert_id 由桥按
--      `alertname|job|error_code|status` 四元组派生（scripts/alert_bridge.py），不含租户；
--      面板跨租户可见，两个租户跑同名规则时必须各占一行（R1）。
--   ② tenant 只来自 JWT，客户端永远不能指定（ADR-0008）。
--   ③ 台账是诊断请求的**副产物**：写入 best-effort，台账坏了不能拖垮排障。
--   ④ 有界留存：默认 30 天 / 每租户 500 条，由 @Scheduled 清理；
--      阈值来自 opspilot.alert.retention-days / max-per-tenant，不写死。
-- 时间列用 TIMESTAMP（与 incidents 一致）；H2 的 PostgreSQL 兼容模式与生产 PostgreSQL
-- 都能直接读。
CREATE TABLE IF NOT EXISTS alert_intake (
    tenant       VARCHAR(64)  NOT NULL,
    alert_id     VARCHAR(128) NOT NULL,
    trace_id     VARCHAR(64),
    service      VARCHAR(128) NOT NULL DEFAULT '',
    env          VARCHAR(32)  NOT NULL DEFAULT '',
    error_code   VARCHAR(64)  NOT NULL DEFAULT '',
    summary      VARCHAR(200) NOT NULL DEFAULT '',
    alert_status VARCHAR(32)  NOT NULL DEFAULT '',
    first_seen   TIMESTAMP    NOT NULL,
    last_seen    TIMESTAMP    NOT NULL,
    seen_count   INT          NOT NULL DEFAULT 1,
    incident_id  VARCHAR(64),
    PRIMARY KEY (tenant, alert_id)
);
CREATE INDEX IF NOT EXISTS idx_alert_intake_tenant_last_seen
    ON alert_intake(tenant, last_seen);
CREATE INDEX IF NOT EXISTS idx_alert_intake_tenant_incident
    ON alert_intake(tenant, incident_id);
