-- MySQL 建表脚本（PostgreSQL 版见同组件 sql/pgsql/sys_http_log.sql；启动期缺表时按当前数据库自动执行，也可手工执行）
-- HTTP请求日志表：字段与 HttpLogEntity 一一对应
-- append-only：delete_time/delete_by 恒为空，保留期清理走物理删除（yomi.httplog.retention）
CREATE TABLE IF NOT EXISTS `sys_http_log`
(
    `id`                      bigint       NOT NULL COMMENT '主键ID（雪花ID）',
    `service_name`            varchar(100) NOT NULL COMMENT '归属业务系统名称',
    `trace_id`                varchar(64)           DEFAULT NULL COMMENT '链路跟踪ID',
    `request_time`            datetime(3)  NOT NULL COMMENT '请求进入时间',
    `duration_ms`             int                   DEFAULT NULL COMMENT '处理耗时（毫秒）',
    `method`                  varchar(10)  NOT NULL COMMENT 'HTTP方法',
    `uri`                     varchar(500) NOT NULL COMMENT '请求路径（不含查询串）',
    `query_string`            varchar(1000)         DEFAULT NULL COMMENT 'URL参数（脱敏后）',
    `status`                  int                   DEFAULT NULL COMMENT '响应状态码',
    `client_ip`               varchar(64)           DEFAULT NULL COMMENT '客户端IP',
    `user_id`                 bigint                DEFAULT NULL COMMENT '用户ID（HttpLogContextEnricher 填充）',
    `request_headers`         text                  DEFAULT NULL COMMENT '请求头JSON（全量，命中键已脱敏）',
    `request_body`            text                  DEFAULT NULL COMMENT '请求体（截断，脱敏后）',
    `request_body_truncated`  tinyint      NOT NULL DEFAULT 0 COMMENT '请求体是否截断：0-否，1-是',
    `response_headers`        text                  DEFAULT NULL COMMENT '响应头JSON（全量，命中键已脱敏）',
    `response_body`           text                  DEFAULT NULL COMMENT '响应体（截断，脱敏后）',
    `response_body_truncated` tinyint      NOT NULL DEFAULT 0 COMMENT '响应体是否截断：0-否，1-是',
    `error_msg`               varchar(1000)         DEFAULT NULL COMMENT '错误信息（HttpLogContextEnricher 填充）',
    `async_started`           tinyint      NOT NULL DEFAULT 0 COMMENT '是否异步请求：0-否，1-是',
    `create_time`             datetime(3)  NOT NULL COMMENT '创建时间（取请求进入时间）',
    `create_by`               bigint                DEFAULT NULL COMMENT '创建人ID',
    `update_time`             datetime(3)  NOT NULL COMMENT '更新时间（等于创建时间）',
    `update_by`               bigint                DEFAULT NULL COMMENT '更新人ID',
    `delete_time`             datetime(3)           DEFAULT NULL COMMENT '删除时间（append-only，恒为空）',
    `delete_by`               bigint                DEFAULT NULL COMMENT '删除人ID（append-only，恒为空）',
    PRIMARY KEY (`id`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_trace_id` (`trace_id`),
    KEY `idx_service_time` (`service_name`, `create_time`),
    KEY `idx_status_time` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='HTTP请求日志表';
