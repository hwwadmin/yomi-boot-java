-- PostgreSQL 建表脚本（MySQL 版见仓库根 sql/mysql/t_sys_idempotency_record.sql；属组件表，需手工执行）
-- 时间列统一 timestamptz(3)（即 timestamp(3) with time zone，瞬时语义、与 Java Instant 对齐；MySQL 版对应 datetime(3)）
-- 约束/索引名在 PG 内是 schema 级唯一（MySQL 是表级），故统一带表名前缀
CREATE TABLE IF NOT EXISTS t_sys_idempotency_record
(
    id              bigint        NOT NULL,
    namespace       varchar(128)  NOT NULL,
    idempotency_key varchar(256)  NOT NULL,
    request_hash    varchar(64),
    status          smallint      NOT NULL DEFAULT 1,
    result_type     varchar(512),
    result_payload  text,
    expire_time     timestamptz(3) NOT NULL,
    create_time     timestamptz(3),
    create_by       bigint,
    update_time     timestamptz(3),
    update_by       bigint,
    delete_time     timestamptz(3),
    delete_by       bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_t_sys_idempotency_record_namespace_key UNIQUE (namespace, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_t_sys_idempotency_record_expire_time ON t_sys_idempotency_record (expire_time);
CREATE INDEX IF NOT EXISTS idx_t_sys_idempotency_record_status_update_time ON t_sys_idempotency_record (status, update_time);
COMMENT ON TABLE t_sys_idempotency_record IS '幂等记录表';
COMMENT ON COLUMN t_sys_idempotency_record.id IS '主键ID（雪花ID）';
COMMENT ON COLUMN t_sys_idempotency_record.namespace IS '幂等命名空间（业务域/接口隔离）';
COMMENT ON COLUMN t_sys_idempotency_record.idempotency_key IS '幂等键';
COMMENT ON COLUMN t_sys_idempotency_record.request_hash IS '请求指纹摘要（SHA-256 hex，同键不同参校验）';
COMMENT ON COLUMN t_sys_idempotency_record.status IS '状态：1处理中 2已完成';
COMMENT ON COLUMN t_sys_idempotency_record.result_type IS '结果类型（全限定类名）';
COMMENT ON COLUMN t_sys_idempotency_record.result_payload IS '结果JSON';
COMMENT ON COLUMN t_sys_idempotency_record.expire_time IS '记录过期时间（归档清理依据）';
COMMENT ON COLUMN t_sys_idempotency_record.create_time IS '创建时间';
COMMENT ON COLUMN t_sys_idempotency_record.create_by IS '创建人ID';
COMMENT ON COLUMN t_sys_idempotency_record.update_time IS '更新时间';
COMMENT ON COLUMN t_sys_idempotency_record.update_by IS '更新人ID';
COMMENT ON COLUMN t_sys_idempotency_record.delete_time IS '删除时间';
COMMENT ON COLUMN t_sys_idempotency_record.delete_by IS '删除人ID';
