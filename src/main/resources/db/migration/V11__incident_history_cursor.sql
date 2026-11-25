-- 事故历史游标分页（2026-11-25，ADR-0038；契约 plans/incident-history-cursor-accept.md）。
-- 只补一个索引，不改既有表：租户扫描按 updated_at 倒序，同一时刻用 incident_id 定序；
-- sla_rank 是由 status/sla_state 派生的排序档位，暂不物化（见 ADR-0038 性能边界）。
CREATE INDEX IF NOT EXISTS idx_incidents_tenant_updated_id
    ON incidents(tenant, updated_at, incident_id);
