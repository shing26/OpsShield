-- L1 回调防重放（2026-10-25 评裁决 2）：nonce 一生一次，窗口同回调时间窗（默认 300s）。
CREATE TABLE IF NOT EXISTS dispatch_nonces (
    nonce   VARCHAR(128) PRIMARY KEY,
    seen_at TIMESTAMP NOT NULL
);
