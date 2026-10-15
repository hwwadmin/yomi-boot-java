-- TableInitializer 测试用 PostgreSQL 建表脚本（验证方言选择、ScriptUtils 解析与自动建表）
CREATE TABLE IF NOT EXISTS table_init_a
(
    id          bigint       NOT NULL,
    name        varchar(64)  NOT NULL,
    create_time timestamptz(3),
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_table_init_a_name ON table_init_a (name);
COMMENT ON TABLE table_init_a IS '初始化测试表 A';

CREATE TABLE IF NOT EXISTS table_init_b
(
    id   bigint NOT NULL,
    a_id bigint NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_table_init_b_a_id UNIQUE (a_id)
);
COMMENT ON TABLE table_init_b IS '初始化测试表 B';

-- 注释行里的分号不应被当作语句分隔符（ScriptUtils 注释处理覆盖）：
-- INSERT INTO table_init_b (id, a_id) VALUES (1, 1);
