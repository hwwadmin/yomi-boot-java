-- PostgreSQL 建表脚本（MySQL 版见同组件 sql/mysql/sys_event.sql；启动期缺表时按当前数据库自动执行，也可手工执行）
-- 时间列统一 timestamptz(3)（即 timestamp(3) with time zone，瞬时语义、与 Java Instant 对齐；MySQL 版对应 datetime(3)）
-- 约束/索引名在 PG 内是 schema 级唯一（MySQL 是表级），故统一带表名前缀；列与语义和 MySQL 版一致

-- 事件收件箱表：字段与 EventInbox 实体一一对应
CREATE TABLE IF NOT EXISTS sys_event_inbox
(
    id           bigint       NOT NULL,
    service_name varchar(100) NOT NULL,
    event_id     varchar(100) NOT NULL,
    topic        varchar(200) NOT NULL,
    payload      text,
    create_time  timestamptz(3),
    create_by    bigint,
    update_time  timestamptz(3),
    update_by    bigint,
    delete_time  timestamptz(3),
    delete_by    bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_event_inbox_service_event_id UNIQUE (service_name, event_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_event_inbox_service_name ON sys_event_inbox (service_name);
CREATE INDEX IF NOT EXISTS idx_sys_event_inbox_create_time ON sys_event_inbox (create_time);
COMMENT ON TABLE sys_event_inbox IS '事件收件箱表';
COMMENT ON COLUMN sys_event_inbox.id IS '主键ID（雪花ID）';
COMMENT ON COLUMN sys_event_inbox.service_name IS '归属业务系统名称';
COMMENT ON COLUMN sys_event_inbox.event_id IS '事件ID（同一topic的所有生产者间唯一，消费幂等键=service_name+event_id）';
COMMENT ON COLUMN sys_event_inbox.topic IS '事件主题';
COMMENT ON COLUMN sys_event_inbox.payload IS '事件负载（一般为 JSON 字符串）';
COMMENT ON COLUMN sys_event_inbox.create_time IS '创建时间';
COMMENT ON COLUMN sys_event_inbox.create_by IS '创建人ID';
COMMENT ON COLUMN sys_event_inbox.update_time IS '更新时间';
COMMENT ON COLUMN sys_event_inbox.update_by IS '更新人ID';
COMMENT ON COLUMN sys_event_inbox.delete_time IS '删除时间';
COMMENT ON COLUMN sys_event_inbox.delete_by IS '删除人ID';

-- 事件发件箱表：字段与 EventOutbox 实体一一对应；status 见 OutboxStatus 枚举
CREATE TABLE IF NOT EXISTS sys_event_outbox
(
    id           bigint       NOT NULL,
    service_name varchar(100) NOT NULL,
    event_id     varchar(100) NOT NULL,
    topic        varchar(200) NOT NULL,
    payload      text,
    status       varchar(20)  NOT NULL DEFAULT 'PENDING',
    fetch_time   timestamptz(3),
    sent_time    timestamptz(3),
    create_time  timestamptz(3),
    create_by    bigint,
    update_time  timestamptz(3),
    update_by    bigint,
    delete_time  timestamptz(3),
    delete_by    bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_event_outbox_service_event_id UNIQUE (service_name, event_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_event_outbox_service_status_create_time ON sys_event_outbox (service_name, status, create_time);
CREATE INDEX IF NOT EXISTS idx_sys_event_outbox_service_status_fetch_time ON sys_event_outbox (service_name, status, fetch_time);
CREATE INDEX IF NOT EXISTS idx_sys_event_outbox_event_id ON sys_event_outbox (event_id);
-- 保留期清理（yomi.event.retention）按 status + sent_time 删除；老库升级需手工执行：
-- CREATE INDEX IF NOT EXISTS idx_sys_event_outbox_status_sent_time ON sys_event_outbox (status, sent_time);
CREATE INDEX IF NOT EXISTS idx_sys_event_outbox_status_sent_time ON sys_event_outbox (status, sent_time);
COMMENT ON TABLE sys_event_outbox IS '事件发件箱表';
COMMENT ON COLUMN sys_event_outbox.id IS '主键ID（雪花ID）';
COMMENT ON COLUMN sys_event_outbox.service_name IS '归属业务系统名称';
COMMENT ON COLUMN sys_event_outbox.event_id IS '事件ID（同一topic的所有生产者间唯一，消费幂等键=service_name+event_id）';
COMMENT ON COLUMN sys_event_outbox.topic IS '事件主题（Kafka topic）';
COMMENT ON COLUMN sys_event_outbox.payload IS '事件负载（一般为 JSON 字符串）';
COMMENT ON COLUMN sys_event_outbox.status IS 'PENDING/FETCHED/SENT_SUCCESS/SENT_FAIL';
COMMENT ON COLUMN sys_event_outbox.fetch_time IS '后台抓取为 FETCHED 的时间';
COMMENT ON COLUMN sys_event_outbox.sent_time IS 'Kafka 发送成功时间';
COMMENT ON COLUMN sys_event_outbox.create_time IS '创建时间';
COMMENT ON COLUMN sys_event_outbox.create_by IS '创建人ID';
COMMENT ON COLUMN sys_event_outbox.update_time IS '更新时间';
COMMENT ON COLUMN sys_event_outbox.update_by IS '更新人ID';
COMMENT ON COLUMN sys_event_outbox.delete_time IS '删除时间';
COMMENT ON COLUMN sys_event_outbox.delete_by IS '删除人ID';

-- 事件发件箱死信表：字段与 EventOutboxDlq 实体一一对应
CREATE TABLE IF NOT EXISTS sys_event_outbox_dlq
(
    id               bigint       NOT NULL,
    service_name     varchar(100) NOT NULL,
    event_outbox_id  bigint       NOT NULL,
    error_msg        text,
    retry_count      integer      NOT NULL DEFAULT 0,
    retry_last_time  timestamptz(3),
    retry_after_time timestamptz(3),
    create_time      timestamptz(3),
    create_by        bigint,
    update_time      timestamptz(3),
    update_by        bigint,
    delete_time      timestamptz(3),
    delete_by        bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_event_outbox_dlq_event_outbox_id UNIQUE (event_outbox_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_event_outbox_dlq_service_name_create_time ON sys_event_outbox_dlq (service_name, create_time);
CREATE INDEX IF NOT EXISTS idx_sys_event_outbox_dlq_retry_last_time ON sys_event_outbox_dlq (retry_last_time);
CREATE INDEX IF NOT EXISTS idx_sys_event_outbox_dlq_retry_after_time ON sys_event_outbox_dlq (retry_after_time);
COMMENT ON TABLE sys_event_outbox_dlq IS '事件发件箱死信队列表';
COMMENT ON COLUMN sys_event_outbox_dlq.id IS '主键ID（雪花ID）';
COMMENT ON COLUMN sys_event_outbox_dlq.service_name IS '归属业务系统名称';
COMMENT ON COLUMN sys_event_outbox_dlq.event_outbox_id IS '发件箱主键（uk_sys_event_outbox_dlq_event_outbox_id）';
COMMENT ON COLUMN sys_event_outbox_dlq.error_msg IS '最近失败信息';
COMMENT ON COLUMN sys_event_outbox_dlq.retry_count IS '已重试次数';
COMMENT ON COLUMN sys_event_outbox_dlq.retry_last_time IS '最近一次重试时间';
COMMENT ON COLUMN sys_event_outbox_dlq.retry_after_time IS '最早可再次重试时间（含 in-flight 占用）';
COMMENT ON COLUMN sys_event_outbox_dlq.create_time IS '创建时间';
COMMENT ON COLUMN sys_event_outbox_dlq.create_by IS '创建人ID';
COMMENT ON COLUMN sys_event_outbox_dlq.update_time IS '更新时间';
COMMENT ON COLUMN sys_event_outbox_dlq.update_by IS '更新人ID';
COMMENT ON COLUMN sys_event_outbox_dlq.delete_time IS '删除时间';
COMMENT ON COLUMN sys_event_outbox_dlq.delete_by IS '删除人ID';
