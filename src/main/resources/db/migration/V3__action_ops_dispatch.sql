-- L1 签名派发（ADR-0028 §6）：confirmed 后系统自动签发——
-- HMAC 材料与 offline/redteam/verify/reference_verifier.py 完全一致：
-- HMAC-SHA256(DISPATCH_KEY, op_id + "." + payload_sha256 + "." + expires_at)
ALTER TABLE action_ops ADD COLUMN IF NOT EXISTS dispatch_target       VARCHAR(255);
ALTER TABLE action_ops ADD COLUMN IF NOT EXISTS dispatch_payload_sha256 VARCHAR(64);
ALTER TABLE action_ops ADD COLUMN IF NOT EXISTS dispatch_hmac         VARCHAR(64);
ALTER TABLE action_ops ADD COLUMN IF NOT EXISTS dispatch_expires_at   TIMESTAMP;
ALTER TABLE action_ops ADD COLUMN IF NOT EXISTS dispatched_at         TIMESTAMP;
ALTER TABLE action_ops ADD COLUMN IF NOT EXISTS dispatch_result       VARCHAR(500);
