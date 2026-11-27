-- 告警历史游标分页（2026-11-27，ADR-0047；契约 plans/alert-history-cursor-accept.md）。
-- 只补一个索引，不改既有表：跨租户按 last_seen 倒序扫描，并列时用 tenant/alert_id 定序。
-- 全 DESC 排序可由该索引反向扫描满足，不新增排序列。
CREATE INDEX IF NOT EXISTS idx_alert_intake_history
    ON alert_intake(last_seen, tenant, alert_id);
