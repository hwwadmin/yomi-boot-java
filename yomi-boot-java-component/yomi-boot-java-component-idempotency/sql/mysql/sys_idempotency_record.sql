-- MySQL 建表脚本（PostgreSQL 版见同组件 sql/pgsql/sys_idempotency_record.sql；启动期缺表时按当前数据库自动执行，也可手工执行）
-- 幂等记录表：字段与 IdempotencyRecord 实体一一对应
CREATE TABLE IF NOT EXISTS `sys_idempotency_record`
(
    `id`               bigint        NOT NULL COMMENT '主键ID（雪花ID）',
    `namespace`        varchar(128)  NOT NULL COMMENT '幂等命名空间（业务域/接口隔离）',
    `idempotency_key`  varchar(256)  NOT NULL COMMENT '幂等键',
    `request_hash`     varchar(64)            DEFAULT NULL COMMENT '请求指纹（同键不同参校验）',
    `status`           tinyint       NOT NULL DEFAULT 1 COMMENT '状态：1处理中 2已完成',
    `result_type`      varchar(512)           DEFAULT NULL COMMENT '结果类型（全限定类名）',
    `result_payload`   mediumtext             DEFAULT NULL COMMENT '结果JSON',
    `expire_time`      datetime(3)   NOT NULL COMMENT '记录过期时间（归档清理依据）',
    `create_time`      datetime(3)            DEFAULT NULL COMMENT '创建时间',
    `create_by`        bigint                 DEFAULT NULL COMMENT '创建人ID',
    `update_time`      datetime(3)            DEFAULT NULL COMMENT '更新时间',
    `update_by`        bigint                 DEFAULT NULL COMMENT '更新人ID',
    `delete_time`      datetime(3)            DEFAULT NULL COMMENT '删除时间',
    `delete_by`        bigint                 DEFAULT NULL COMMENT '删除人ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_namespace_key` (`namespace`, `idempotency_key`),
    KEY `idx_expire_time` (`expire_time`),
    KEY `idx_status_update_time` (`status`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='幂等记录表';
