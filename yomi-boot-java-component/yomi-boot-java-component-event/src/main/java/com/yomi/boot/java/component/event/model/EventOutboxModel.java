package com.yomi.boot.java.component.event.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 发件箱查询视图（{@code pageOutbox} 为概要、{@code findOutbox} 为全量含 payload）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
public class EventOutboxModel {

    /** 主键ID（雪花） */
    private Long id;

    /** 事件ID（服务内唯一） */
    private String eventId;

    /** Kafka Topic */
    private String topic;

    /** 状态 */
    private OutboxStatus status;

    /** 抓取为 FETCHED 的时间 */
    private Instant fetchTime;

    /** 发送成功时间 */
    private Instant sentTime;

    /** 创建时间 */
    private Instant createTime;

    /** 事件负载 JSON（仅 findOutbox 详情返回；页面概要查询为 null） */
    private String payload;

}
