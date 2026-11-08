-- MySQL 建表脚本（PostgreSQL 版见仓库根 sql/pgsql/t_sys_event.sql；属组件表，需手工执行）
-- 事件收件箱表：字段与 EventInbox 实体一一对应
CREATE TABLE IF NOT EXISTS `t_sys_event_inbox`
(
    `id`           bigint        NOT NULL COMMENT '主键ID（雪花ID）',
    `service_name` varchar(100)  NOT NULL COMMENT '归属业务系统名称',
    `event_id`     varchar(100)  NOT NULL COMMENT '事件ID（同一topic的所有生产者间唯一，消费幂等键=service_name+event_id）',
    `topic`        varchar(200)  NOT NULL COMMENT '事件主题',
    `payload`      mediumtext             DEFAULT NULL COMMENT '事件负载（一般为 JSON 字符串）',
    `create_time`  datetime(3)            DEFAULT NULL COMMENT '创建时间',
    `create_by`    bigint                 DEFAULT NULL COMMENT '创建人ID',
    `update_time`  datetime(3)            DEFAULT NULL COMMENT '更新时间',
    `update_by`    bigint                 DEFAULT NULL COMMENT '更新人ID',
    `delete_time`  datetime(3)            DEFAULT NULL COMMENT '删除时间',
    `delete_by`    bigint                 DEFAULT NULL COMMENT '删除人ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_service_event_id` (`service_name`, `event_id`),
    KEY `idx_service_name` (`service_name`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='事件收件箱表';

-- 事件发件箱表：字段与 EventOutbox 实体一一对应；status 见 OutboxStatus 枚举
CREATE TABLE IF NOT EXISTS `t_sys_event_outbox`
(
    `id`           bigint        NOT NULL COMMENT '主键ID（雪花ID）',
    `service_name` varchar(100)  NOT NULL COMMENT '归属业务系统名称',
    `event_id`     varchar(100)  NOT NULL COMMENT '事件ID（同一topic的所有生产者间唯一，消费幂等键=service_name+event_id）',
    `topic`        varchar(200)  NOT NULL COMMENT '事件主题（Kafka topic）',
    `payload`      mediumtext             DEFAULT NULL COMMENT '事件负载（一般为 JSON 字符串）',
    `status`       varchar(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/FETCHED/SENT_SUCCESS/SENT_FAIL',
    `fetch_time`   datetime(3)            DEFAULT NULL COMMENT '后台抓取为 FETCHED 的时间',
    `sent_time`    datetime(3)            DEFAULT NULL COMMENT 'Kafka 发送成功时间',
    `create_time`  datetime(3)            DEFAULT NULL COMMENT '创建时间',
    `create_by`    bigint                 DEFAULT NULL COMMENT '创建人ID',
    `update_time`  datetime(3)            DEFAULT NULL COMMENT '更新时间',
    `update_by`    bigint                 DEFAULT NULL COMMENT '更新人ID',
    `delete_time`  datetime(3)            DEFAULT NULL COMMENT '删除时间',
    `delete_by`    bigint                 DEFAULT NULL COMMENT '删除人ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_service_event_id` (`service_name`, `event_id`),
    KEY `idx_service_status_create_time` (`service_name`, `status`, `create_time`),
    KEY `idx_service_status_fetch_time` (`service_name`, `status`, `fetch_time`),
    KEY `idx_event_id` (`event_id`),
    -- 保留期清理（yomi.event.retention）按 status + sent_time 删除；老库升级需手工执行：
    -- ALTER TABLE t_sys_event_outbox ADD KEY idx_status_sent_time (status, sent_time);
    KEY `idx_status_sent_time` (`status`, `sent_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='事件发件箱表';

-- 事件发件箱死信表：字段与 EventOutboxDlq 实体一一对应
CREATE TABLE IF NOT EXISTS `t_sys_event_outbox_dlq`
(
    `id`               bigint       NOT NULL COMMENT '主键ID（雪花ID）',
    `service_name`     varchar(100) NOT NULL COMMENT '归属业务系统名称',
    `event_outbox_id`  bigint       NOT NULL COMMENT '发件箱主键（uk_event_outbox_id）',
    `error_msg`        mediumtext            DEFAULT NULL COMMENT '最近失败信息',
    `retry_count`      int          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `retry_last_time`  datetime(3)           DEFAULT NULL COMMENT '最近一次重试时间',
    `retry_after_time` datetime(3)           DEFAULT NULL COMMENT '最早可再次重试时间（含 in-flight 占用）',
    `create_time`      datetime(3)           DEFAULT NULL COMMENT '创建时间',
    `create_by`        bigint                DEFAULT NULL COMMENT '创建人ID',
    `update_time`      datetime(3)           DEFAULT NULL COMMENT '更新时间',
    `update_by`        bigint                DEFAULT NULL COMMENT '更新人ID',
    `delete_time`      datetime(3)           DEFAULT NULL COMMENT '删除时间',
    `delete_by`        bigint                DEFAULT NULL COMMENT '删除人ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_outbox_id` (`event_outbox_id`),
    KEY `idx_service_name_create_time` (`service_name`, `create_time`),
    KEY `idx_retry_last_time` (`retry_last_time`),
    KEY `idx_retry_after_time` (`retry_after_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='事件发件箱死信队列表';
