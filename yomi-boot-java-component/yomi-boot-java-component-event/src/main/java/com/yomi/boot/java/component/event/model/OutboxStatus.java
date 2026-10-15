package com.yomi.boot.java.component.event.model;

/**
 * 发件箱状态，与列 {@code sys_event_outbox.status} 一致
 *
 * @author zwei
 */
public enum OutboxStatus {

    /** 待抓取投递 */
    PENDING,
    /** 已抓取，发送中 */
    FETCHED,
    /** 已发送到 Kafka */
    SENT_SUCCESS,
    /** 发送失败（通常伴随 DLQ 记录） */
    SENT_FAIL

}
