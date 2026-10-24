-- L1 受控行动契约（ADR-0028）：一次提案的生命周期。
-- 状态机：proposed -> confirmed -> dispatched -> executed|failed|expired|revoked（派发列在 V3 补）
-- confirm_token_hash：只存哈希——token 一次性，明文仅在 propose 响应里出现一次。
CREATE TABLE IF NOT EXISTS action_ops (
    op_id              VARCHAR(64)  PRIMARY KEY,
    action_class       VARCHAR(32)  NOT NULL,
    command            VARCHAR(1000) NOT NULL,
    ref                VARCHAR(255) NOT NULL,
    breadcrumb         VARCHAR(500) NOT NULL,
    service            VARCHAR(128) NOT NULL,
    auth_level         INT          NOT NULL,
    tenant             VARCHAR(128) NOT NULL,
    proposed_by        VARCHAR(128) NOT NULL,
    proposed_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state              VARCHAR(24)  NOT NULL,
    confirm_token_hash VARCHAR(128),
    confirm_expires_at TIMESTAMP,
    confirmed_by       VARCHAR(128),
    confirmed_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_action_ops_state ON action_ops(state);
CREATE INDEX IF NOT EXISTS idx_action_ops_tenant ON action_ops(tenant);
