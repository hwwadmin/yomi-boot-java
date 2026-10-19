package com.yomi.boot.java.component.event.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yomi.boot.java.component.ds.BaseEntity;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 表 sys_event_outbox；建表脚本在仓库根目录 {@code sql/mysql|pgsql/sys_event.sql}
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("sys_event_outbox")
public class EventOutbox extends BaseEntity {

    /** 归属服务名，与 event_id 组成唯一键 */
    private String serviceName;

    /** 事件 ID，与 serviceName 组成唯一键 */
    private String eventId;

    /** Kafka Topic */
    private String topic;

    /** 事件载荷 JSON */
    private String payload;

    /** 发件箱状态（待发送/已抓取/已发送等） */
    private OutboxStatus status;

    /** 后台抓取为 FETCHED 的时间 */
    private Instant fetchTime;

    /** Kafka 发送成功时间 */
    private Instant sentTime;

}
