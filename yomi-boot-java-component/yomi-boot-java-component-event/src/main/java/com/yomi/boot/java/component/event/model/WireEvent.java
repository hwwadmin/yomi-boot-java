package com.yomi.boot.java.component.event.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka 消息体 JSON 结构，与 Go {@code WireEvent} 字段一致
 *
 * @author zwei
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WireEvent {

    /**
     * 幂等键与 Kafka message key。
     * <p>必须在<b>同一 topic 的所有生产者之间唯一</b>：消费侧幂等键是 {@code service_name + eventId}，
     * 不同生产者撞 id 的消息会被当作重复消息跳过（不执行业务）</p>
     */
    private String eventId;

    /** 逻辑主题，与 Kafka topic 一致 */
    private String topic;

    /** 业务负载，一般为 JSON 字符串 */
    private String payload;

}
