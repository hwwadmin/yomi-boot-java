-- PostgreSQL 建表脚本（MySQL 版见仓库根 sql/mysql/sys_http_log.sql；属组件表，需手工执行）
-- 时间列统一 timestamptz(3)（即 timestamp(3) with time zone，瞬时语义、与 Java Instant 对齐；MySQL 版对应 datetime(3)）
-- 索引名在 PG 内是 schema 级唯一（MySQL 是表级），故统一带表名前缀
-- 布尔列用 boolean（实体字段是 Boolean，MySQL 版对应 tinyint）
-- append-only：delete_time/delete_by 恒为空，保留期清理走物理删除（yomi.httplog.retention）
CREATE TABLE IF NOT EXISTS sys_http_log
(
    id                      bigint       NOT NULL,
    service_name            varchar(100) NOT NULL,
    trace_id                varchar(64),
    request_time            timestamptz(3) NOT NULL,
    duration_ms             integer,
    method                  varchar(10)  NOT NULL,
    uri                     varchar(500) NOT NULL,
    query_string            varchar(1000),
    status                  integer,
    client_ip               varchar(64),
    user_id                 bigint,
    request_headers         text,
    request_body            text,
    request_body_truncated  boolean      NOT NULL DEFAULT false,
    response_headers        text,
    response_body           text,
    response_body_truncated boolean      NOT NULL DEFAULT false,
    error_msg               varchar(1000),
    async_started           boolean      NOT NULL DEFAULT false,
    create_time             timestamptz(3) NOT NULL,
    create_by               bigint,
    update_time             timestamptz(3) NOT NULL,
    update_by               bigint,
    delete_time             timestamptz(3),
    delete_by               bigint,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_sys_http_log_create_time ON sys_http_log (create_time);
CREATE INDEX IF NOT EXISTS idx_sys_http_log_trace_id ON sys_http_log (trace_id);
CREATE INDEX IF NOT EXISTS idx_sys_http_log_service_time ON sys_http_log (service_name, create_time);
CREATE INDEX IF NOT EXISTS idx_sys_http_log_status_time ON sys_http_log (status, create_time);
CREATE INDEX IF NOT EXISTS idx_sys_http_log_service_user_time ON sys_http_log (service_name, user_id, create_time);
COMMENT ON TABLE sys_http_log IS 'HTTP请求日志表';
COMMENT ON COLUMN sys_http_log.id IS '主键ID（雪花ID）';
COMMENT ON COLUMN sys_http_log.service_name IS '归属业务系统名称';
COMMENT ON COLUMN sys_http_log.trace_id IS '链路跟踪ID';
COMMENT ON COLUMN sys_http_log.request_time IS '请求进入时间';
COMMENT ON COLUMN sys_http_log.duration_ms IS '处理耗时（毫秒）';
COMMENT ON COLUMN sys_http_log.method IS 'HTTP方法';
COMMENT ON COLUMN sys_http_log.uri IS '请求路径（不含查询串）';
COMMENT ON COLUMN sys_http_log.query_string IS 'URL参数（脱敏后）';
COMMENT ON COLUMN sys_http_log.status IS '响应状态码';
COMMENT ON COLUMN sys_http_log.client_ip IS '客户端IP';
COMMENT ON COLUMN sys_http_log.user_id IS '用户ID（HttpLogContextEnricher 填充）';
COMMENT ON COLUMN sys_http_log.request_headers IS '请求头JSON（全量，命中键已脱敏）';
COMMENT ON COLUMN sys_http_log.request_body IS '请求体（截断，脱敏后）';
COMMENT ON COLUMN sys_http_log.request_body_truncated IS '请求体是否截断';
COMMENT ON COLUMN sys_http_log.response_headers IS '响应头JSON（全量，命中键已脱敏）';
COMMENT ON COLUMN sys_http_log.response_body IS '响应体（截断，脱敏后）';
COMMENT ON COLUMN sys_http_log.response_body_truncated IS '响应体是否截断';
COMMENT ON COLUMN sys_http_log.error_msg IS '错误信息（HttpLogContextEnricher 填充）';
COMMENT ON COLUMN sys_http_log.async_started IS '是否异步请求';
COMMENT ON COLUMN sys_http_log.create_time IS '创建时间（取请求进入时间）';
COMMENT ON COLUMN sys_http_log.create_by IS '创建人ID';
COMMENT ON COLUMN sys_http_log.update_time IS '更新时间（等于创建时间）';
COMMENT ON COLUMN sys_http_log.update_by IS '更新人ID';
COMMENT ON COLUMN sys_http_log.delete_time IS '删除时间（append-only，恒为空）';
COMMENT ON COLUMN sys_http_log.delete_by IS '删除人ID（append-only，恒为空）';
