package com.yomi.boot.java.component.kafka;

import org.springframework.kafka.support.SendResult;

import java.util.concurrent.TimeUnit;

/**
 * 同步发送：{@code send()} 阻塞直到 broker 确认或超时。
 * <p>示例：{@code KafkaUtils.sync().topic("t").payload(msg).send()}</p>
 * <p>超时仅表示放弃等待，不保证消息一定未送达。</p>
 * <p>阻塞边界：{@link #timeout} 只约束提交后的确认等待；元数据不可用时 {@code template.send} 自身还会在
 * 客户端最长阻塞 max.block.ms（默认 60 秒）并同步抛错，故最坏阻塞 ≈ max.block.ms + timeout；
 * 上限要更紧可调小 producer 的 max.block.ms。</p>
 *
 * @author zwei
 */
public final class KafkaSyncSender extends KafkaSendBuilderBase<KafkaSyncSender> {

    /** send 默认等待 60 秒（不含提交期的元数据等待） */
    private static final long DEFAULT_SEND_TIMEOUT_SECONDS = 60L;

    /** 提交后等待 broker 确认的时长 */
    private long timeout = DEFAULT_SEND_TIMEOUT_SECONDS;

    /** 等待时长单位 */
    private TimeUnit timeoutUnit = TimeUnit.SECONDS;

    /** 链式 API 返回自身 */
    @Override
    protected KafkaSyncSender self() {
        return this;
    }

    /** 设置提交后等待 broker 确认的最长时间，默认 60 秒；不含提交期的元数据等待（最长 max.block.ms） */
    public KafkaSyncSender timeout(long timeout, TimeUnit unit) {
        ensureOpen();
        this.timeout = timeout;
        this.timeoutUnit = unit;
        return this;
    }

    /**
     * 校验通过后提交发送并阻塞等待结果；调用即作废本实例（超时或失败同样作废）。
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
