-- G1（2026-10-22）：账号库基线。原 schema.sql 由 spring.sql.init 每次启动执行；
-- 改为 Flyway V1 后，"表在"之外还多了一条可查的版本事实（flyway_schema_history）。
-- 对空库：本文件负责建表；对已有库：Flyway 会幂等执行本文件（IF NOT EXISTS，不丢数据）
-- 并留下版本记录。DDL 与原 schema.sql 逐字保持一致，避免两套口径。
CREATE TABLE IF NOT EXISTS users (
    sub          VARCHAR(64)  PRIMARY KEY,
    pass_bcrypt  VARCHAR(100) NOT NULL,
    tenant       VARCHAR(64)  NOT NULL,
    auth_level   INT          NOT NULL CHECK (auth_level BETWEEN 1 AND 9),
    role         VARCHAR(32)  NOT NULL,
    disabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    token_ver    INT          NOT NULL DEFAULT 1,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
