package com.yomi.boot.java.component.event.consume;

/**
 * 消费处理结果，供业务映射 Kafka 提交 offset 策略
 *
 * @author zwei
 */
public enum EventConsumeResult {

    /** 可提交 offset（含重复消息） */
    SUCCESS,
    /**
     * 本条未处理成功，需要调用方在 retry 动作里显式退回重投（如 Kafka 的 {@code acknowledgment.nack(...)}）。
     * <p>注意：仅"不提交 offset"不会重投——manual ack 模式下提交的是已确认的最大 offset+1，
     * 同分区后续消息一旦提交，本条就被永久跳过
     */
    RETRY,
    /** 毒消息，提交 offset 并丢弃 */
    DROP

}
