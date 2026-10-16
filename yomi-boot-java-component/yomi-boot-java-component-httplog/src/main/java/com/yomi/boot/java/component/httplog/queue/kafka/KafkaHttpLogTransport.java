package com.yomi.boot.java.component.httplog.queue.kafka;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.queue.AbstractBufferedTransport;
import com.yomi.boot.java.component.httplog.queue.HttpLogBatchHandler;
import com.yomi.boot.java.component.httplog.queue.HttpLogConsumer;
import com.yomi.boot.java.component.httplog.queue.HttpLogSink;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import com.yomi.boot.java.component.kafka.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * kafka 模式传输：L1 有界缓冲 → 发送线程异步发送 → 批量消费 → 回调
 * <p>发送走 component-kafka 的 {@code KafkaUtils.async()}（producer 自带批量）；消费端自建批量容器
 * （不复用宿主 @KafkaListener 的 listener 配置），按批提交（AckMode.BATCH），处理失败只计数不抛出
 * ——允许重复（雪花主键收敛），也允许丢弃，绝不让毒批阻塞分区。
 * <p>发送前完成 body 补脱敏：进入 topic 的即为脱敏后数据。
 *
 * @author zwei
 */
@Slf4j
public class KafkaHttpLogTransport extends AbstractBufferedTransport implements HttpLogSink, HttpLogConsumer {

    /** Kafka 消费者工厂（复用宿主 spring.kafka 配置） */
    private final ConsumerFactory<?, ?> consumerFactory;

    /** 日志 topic */
    private final String topic;

    /** 消费组 ID */
    private final String group;

    /** 缓冲 → Kafka 的发送线程 */
    private volatile Thread sender;

    /** 批量消费监听容器 */
    private ConcurrentMessageListenerContainer<String, String> container;

    public KafkaHttpLogTransport(HttpLogProperties properties, HttpLogKeys keys,
                                 ConsumerFactory<?, ?> consumerFactory, HttpLogDesensitizer desensitizer,
                                 HttpLogMetrics metrics) {
        super("kafka", properties, metrics, desensitizer);
        this.consumerFactory = consumerFactory;
        this.topic = keys.kafkaTopic(properties);
        this.group = keys.kafkaConsumerGroup(properties);
    }

    /** 启动 Kafka 生产者 */
    @Override
    public void start(HttpLogBatchHandler handler) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        sender = startLoopThread("httplog-kafka-sender", "kafka-send", "send-failed", "kafka-send-loop");
        container = buildContainer(handler);
        container.start();
        log.info("[httplog]kafka 模式已启动: topic={}, group={}, concurrency={}",
                topic, group, properties.getKafka().getConcurrency());
    }

    /** flush 并关闭生产者 */
    @Override
    public void stop(Duration timeout) {
        flush(timeout);
        running.set(false);
        stopThread(sender, timeout);
        if (container != null) {
            container.stop();
        }
    }

    /** 将一批记录 JSON 序列化后异步发送到 topic */
    @Override
    protected void writeBatch(List<HttpLogRecord> batch) {
        for (HttpLogRecord record : batch) {
            var builder = KafkaUtils.async().topic(topic).key(String.valueOf(record.getId()))
                    .payload(JsonUtils.toJson(record));
            if (StringUtils.isNotBlank(properties.getKafka().getTemplateBeanName())) {
                builder.template(properties.getKafka().getTemplateBeanName());
            }
            builder.send().whenComplete((result, error) -> {
                if (error != null) {
                    metrics.failed("kafka-send", error);
                    metrics.dropped("send-failed", 1);
                }
            });
        }
    }

    /** 自建批量消费容器：以 spring.kafka.* 为基线，覆盖组与提交策略，避免与宿主 listener 互相污染 */
    private ConcurrentMessageListenerContainer<String, String> buildContainer(HttpLogBatchHandler handler) {
        Map<String, Object> configs = new HashMap<>(consumerFactory.getConfigurationProperties());
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        ConsumerFactory<String, String> factory =
                new DefaultKafkaConsumerFactory<>(configs, new StringDeserializer(), new StringDeserializer());
        ContainerProperties containerProperties = new ContainerProperties(topic);
        containerProperties.setGroupId(group);
        containerProperties.setAckMode(ContainerProperties.AckMode.BATCH);
        containerProperties.setMessageListener(
                (BatchMessageListener<String, String>) records -> onKafkaBatch(records, handler));
        ConcurrentMessageListenerContainer<String, String> listener =
                new ConcurrentMessageListenerContainer<>(factory, containerProperties);
        listener.setConcurrency(properties.getKafka().getConcurrency());
        listener.setBeanName("httpLogKafkaContainer");
        return listener;
    }

    /** 批处理：失败只计数不抛出（允许丢；抛出会让容器无界重试阻塞分区） */
    private void onKafkaBatch(List<ConsumerRecord<String, String>> records, HttpLogBatchHandler handler) {
        List<HttpLogRecord> batch = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            HttpLogRecord parsed = parse(record.value());
            if (parsed != null) {
                batch.add(parsed);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            handler.onBatch(batch);
        } catch (Throwable t) {
            metrics.failed("kafka-consume", t);
            metrics.dropped("consume-failed", batch.size());
        }
    }

    /** 解析线协议消息 */
    private static HttpLogRecord parse(String payload) {
        try {
            return JsonUtils.parse(payload, HttpLogRecord.class);
        } catch (Exception e) {
            log.warn("[httplog]反序列化失败，丢弃该条: {}", e.getMessage());
            return null;
        }
    }

}
