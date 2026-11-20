-- 事故单（2026-11-20）：把只存在于浏览器 localStorage 的事故升级为服务端业务对象。
-- 设计约束：
--   ① tenant 是硬隔离维度，所有查询必须带 tenant 条件（禁止跨租户共享）；
--   ② client_id 由前端生成，唯一索引 (tenant, client_id) 让创建可幂等重放；
--   ③ diagnosis/timeline 是渲染所需的快照，按 CLOB 存 JSON——结构演进不阻塞 DDL；
--   ④ 不设删除：结单是状态而不是删除，保留可追溯性。
CREATE TABLE IF NOT EXISTS incidents (
    incident_id    VARCHAR(64)   PRIMARY KEY,
    tenant         VARCHAR(64)   NOT NULL,
    client_id      VARCHAR(64)   NOT NULL,
    title          VARCHAR(120)  NOT NULL,
    query          VARCHAR(8192) NOT NULL,
    service        VARCHAR(128)  NOT NULL DEFAULT '',
    error_code     VARCHAR(64)   NOT NULL DEFAULT '',
    env            VARCHAR(32)   NOT NULL DEFAULT '',
    alert_id       VARCHAR(128)  NOT NULL DEFAULT '',
    source         VARCHAR(16)   NOT NULL,
    priority       VARCHAR(8)    NOT NULL DEFAULT '',
    status         VARCHAR(16)   NOT NULL,
    owner          VARCHAR(64),
    created_by     VARCHAR(64)   NOT NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at      TIMESTAMP,
    closed_by      VARCHAR(64),
    close_note     VARCHAR(500),
    diagnosis_json CLOB,
    timeline_json  CLOB
);
CREATE INDEX IF NOT EXISTS idx_incidents_tenant_updated ON incidents(tenant, updated_at);
CREATE INDEX IF NOT EXISTS idx_incidents_tenant_status ON incidents(tenant, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_incidents_tenant_client ON incidents(tenant, client_id);
