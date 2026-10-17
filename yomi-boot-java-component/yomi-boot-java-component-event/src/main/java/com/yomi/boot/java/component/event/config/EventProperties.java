package com.yomi.boot.java.component.event.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 配置前缀 {@code yomi.event}
 *
 * @author zwei
 */
@Slf4j
@Data
@ConfigurationProperties(prefix = "yomi.event")
public class EventProperties {

    /** 写发件箱 + 后台投递 Kafka */
    private boolean publisherEnabled = false;

    /** 装配收件箱幂等消费守卫 */
    private boolean consumerEnabled = false;

    /** 未配置时从 {@code spring.application.name} 推导 */
    private String serviceName;

    /** 多 Kafka 集群时指定 KafkaTemplate Bean 名 */
    private String kafkaTemplateBeanName;

    /** 抓取待发件箱行的轮询间隔 */
    private Duration fetchInterval = Duration.ofSeconds(1);

    /** 每轮抓取发件箱的最大条数 */
    private int fetchBatchSize = 500;
    /**
     * 单条事件序列化后（含 eventId/topic 信封）的字节上限，发布超过即抛业务异常。
     * <p>
     * 必须不超过 Kafka 侧 {@code max.request.size} 与 topic 的 {@code max.message.bytes}
     * 减去 message key、头部与记录开销后的余量：Kafka 默认上限均为 1 MiB，
     * 默认值 1000000 字节（约 0.95 MiB）即为留出该余量；调大 Kafka 上限后可同步调大本值。
     * 发布时即校验：超限事件直接失败，不会进入发件箱与 DLQ 重试。
     */
    private int maxPayloadBytes = 1_000_000;

    /** 并发发送线程数 */
    private int dispatchParallelism = 2;

    /** 发送队列容量，满则恢复 PENDING */
    private int dispatchQueueCapacity = 256;

    /** FETCHED 超过该时长未发送成功则回收 */
    private Duration fetchedTimeout = Duration.ofMinutes(5);

    /** DLQ 扫描间隔 */
    private Duration dlqScanInterval = Duration.ofSeconds(1);

    /** 每轮扫描 DLQ 的最大条数 */
    private int dlqBatchSize = 500;

    /** DLQ 最大自动重试次数，0 表示关闭自动重试 */
    private int dlqMaxRetry = 30;
    /**
     * DLQ 投递占用 {@code retry_after_time} 的时长，占用期内同一行不会被并行重试；
     * 建议不小于 Kafka 同步发送超时（默认约 60s）
     */
    private Duration dlqInFlightLease = Duration.ofSeconds(90);

    /** 消费侧获取锁的最长等待 */
    private Duration consumeLockWait = Duration.ofSeconds(30);

    /** 持锁租期；0 表示 Redisson 看门狗 */
    private Duration consumeLockLease = Duration.ZERO;

    /** 过期数据清理配置（默认关闭） */
    private Retention retention = new Retention();

    /**
     * 保留期清理配置（{@code yomi.event.retention.*}）
     */
    @Data
    public static class Retention {

        /** 是否启用过期清理 */
        private boolean enabled = false;

        /** 发件箱已成功行保留期：SENT_SUCCESS 且 sent_time 早于该值的行被物理删除 */
        private Duration outboxRetention = Duration.ofDays(14);

        /**
         * 收件箱清理开关；默认关闭。
         * <p>收件箱行是"只消费一次"的依据：保留期必须不短于事件可能被重投的最长时间
         * （Kafka topic retention，默认 7 天）；保留期短于这个窗口时，超期重投的事件会被当作新消息再消费一次</p>
         */
        private boolean inboxRetentionEnabled = false;

        /** 收件箱行保留期（需同时开启 inbox-retention-enabled） */
        private Duration inboxRetention = Duration.ofDays(14);

        /** 扫描间隔 */
        private Duration scanInterval = Duration.ofHours(1);

        /** 每批删除行数 */
        private int batchSize = 1000;

        /** 单轮最多删除行数（防止一次清理长时间占用数据库） */
        private int maxDeletePerRun = 50_000;

    }

    /** 任一侧启用时校验取值 */
    public void validate() {
        if (!publisherEnabled && !consumerEnabled) {
            return;
        }
        if (publisherEnabled) {
            validatePublisher();
        }
        if (consumerEnabled) {
            validateConsumer();
        }
        validateRetention();
    }

    /** 发布侧配置自检 */
    private void validatePublisher() {
        requirePositive(fetchInterval, "yomi.event.fetch-interval");
        if (fetchBatchSize <= 0) {
            throw new IllegalStateException("yomi.event.fetch-batch-size 必须大于 0");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalStateException("yomi.event.max-payload-bytes 必须大于 0");
        }
        if (dispatchParallelism <= 0) {
            throw new IllegalStateException("yomi.event.dispatch-parallelism 必须大于 0");
        }
        if (dispatchQueueCapacity <= 0) {
            throw new IllegalStateException("yomi.event.dispatch-queue-capacity 必须大于 0");
        }
        requirePositive(fetchedTimeout, "yomi.event.fetched-timeout");
        requirePositive(dlqScanInterval, "yomi.event.dlq-scan-interval");
        if (dlqBatchSize <= 0) {
            throw new IllegalStateException("yomi.event.dlq-batch-size 必须大于 0");
        }
        if (dlqMaxRetry < 0) {
            throw new IllegalStateException("yomi.event.dlq-max-retry 不能为负数");
        }
        requirePositive(dlqInFlightLease, "yomi.event.dlq-in-flight-lease");
    }

    /** 消费侧配置自检 */
    private void validateConsumer() {
        requirePositive(consumeLockWait, "yomi.event.consume-lock-wait");
        if (consumeLockLease != null && consumeLockLease.isNegative()) {
            throw new IllegalStateException("yomi.event.consume-lock-lease 不能为负数");
        }
    }

    /** 保留期清理配置自检 */
    private void validateRetention() {
        if (retention == null || !retention.isEnabled()) {
            if (retention != null && retention.isInboxRetentionEnabled()) {
                throw new IllegalStateException(
                        "yomi.event.retention.inbox-retention-enabled=true 需要同时开启 yomi.event.retention.enabled=true");
            }
            return;
        }
        requirePositive(retention.getOutboxRetention(), "yomi.event.retention.outbox-retention");
        requirePositive(retention.getScanInterval(), "yomi.event.retention.scan-interval");
        if (retention.getScanInterval().compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalStateException("yomi.event.retention.scan-interval 必须大于等于 1 分钟");
        }
        if (retention.getBatchSize() <= 0) {
            throw new IllegalStateException("yomi.event.retention.batch-size 必须大于 0");
        }
        if (retention.getMaxDeletePerRun() <= 0) {
            throw new IllegalStateException("yomi.event.retention.max-delete-per-run 必须大于 0");
        }
        if (!retention.isInboxRetentionEnabled()) {
            return;
        }
        requirePositive(retention.getInboxRetention(), "yomi.event.retention.inbox-retention");
        if (retention.getInboxRetention().compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalStateException(
                    "yomi.event.retention.inbox-retention 必须大于等于 1 天（明显配错：保留期过短会让重投的事件被二次消费）");
        }
        if (retention.getInboxRetention().compareTo(Duration.ofDays(7)) < 0) {
            log.warn("[事件]yomi.event.retention.inbox-retention={} 短于 Kafka 默认 topic retention(7 天)："
                            + "超过保留期后仍在重投的事件会被当作新消息再消费一次，请确认小于该窗口",
                    retention.getInboxRetention());
        }
    }

    /** 校验 Duration 须大于 0 */
    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 必须大于 0");
        }
    }

}
