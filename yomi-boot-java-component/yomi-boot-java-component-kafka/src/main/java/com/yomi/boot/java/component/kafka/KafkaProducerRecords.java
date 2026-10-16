package com.yomi.boot.java.component.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 构建 {@link ProducerRecord}，以及同步等待 {@link KafkaTemplate#send} 结果。
 *
 * @author zwei
 */
final class KafkaProducerRecords {

    private KafkaProducerRecords() {
    }

    /** 组装待发送记录（先写 Map 中的 Header，再写 Iterable） */
    static ProducerRecord<String, Object> create(
            String topic,
            String key,
            Object payload,
            Integer partition,
            Map<String, byte[]> headerMap,
            Iterable<Header> headerIterable) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, partition, key, payload);
        applyHeaders(record, headerMap, headerIterable);
        return record;
    }

    /** 为 ProducerRecord 附加 Header */
    private static void applyHeaders(
            ProducerRecord<String, Object> record,
            Map<String, byte[]> headerMap,
            Iterable<Header> headerIterable) {
        if (headerMap != null) {
            headerMap.forEach((name, value) -> record.headers().add(name, value));
        }
        if (headerIterable != null) {
            for (Header header : headerIterable) {
                record.headers().add(header.key(), header.value());
            }
        }
    }

    /**
     * 同步等待发送完成；超时或失败抛 {@link IllegalStateException}，消息中带 topic。
     * <p>元数据等待发生在 {@code template.send} 内部（最长客户端 max.block.ms），先于 {@code get(timeout)}；
     * 该阶段超时会同步抛错，并被归为"发送失败"而不是"发送超时"。</p>
     */
    static SendResult<String, Object> awaitSend(
            KafkaTemplate<String, Object> template,
            ProducerRecord<String, Object> record,
            String topic,
            long timeout,
            TimeUnit unit) {
        try {
            return template.send(record).get(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka 消息发送被中断, topic=" + topic, e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Kafka 消息发送超时, topic=" + topic + ", timeout=" + timeout + " " + unit, e);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka 消息发送失败, topic=" + topic, e);
        }
    }

}
