package com.yomi.boot.java.component.kafka;

import org.springframework.kafka.support.SendResult;

import java.util.concurrent.TimeUnit;

/**
 * 同步发送：{@code send()} 阻塞直到 broker 确认或超时。
 * <p>示例：{@code KafkaUtils.sync().topic("t").payload(msg).send()}</p>
 * <p>超时仅表示放弃等待，不保证消息一定未送达。</p>
 *
 * @author zwei
 */
public final class KafkaSyncSender extends KafkaSendBuilderBase<KafkaSyncSender> {

    /** 默认等待 60 秒，与 Kafka 客户端 max.block.ms 默认量级一致 */
    private static final long DEFAULT_SEND_TIMEOUT_SECONDS = 60L;

    /** send 最大等待时长 */
    private long timeout = DEFAULT_SEND_TIMEOUT_SECONDS;

    /** 等待时长单位 */
    private TimeUnit timeoutUnit = TimeUnit.SECONDS;

    /** 链式 API 返回自身 */
    @Override
    protected KafkaSyncSender self() {
        return this;
    }

    /** 设置 {@link #send()} 的最大等待时间，默认 60 秒 */
    public KafkaSyncSender timeout(long timeout, TimeUnit unit) {
        ensureOpen();
        this.timeout = timeout;
        this.timeoutUnit = unit;
        return this;
    }

    /**
     * 校验通过后提交发送并阻塞等待结果；提交前本实例即作废（超时或失败亦不可再 send）。
     */
    public SendResult<String, Object> send() {
        ensureOpen();
        validateBeforeSend();
        var template = KafkaTemplateHolder.resolve(templateBeanName());
        var record = toProducerRecord();
        terminate();
        return KafkaProducerRecords.awaitSend(template, record, topic(), timeout, timeoutUnit);
    }

}
