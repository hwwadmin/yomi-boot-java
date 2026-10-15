package com.yomi.boot.java.component.kafka;

import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

/**
 * 异步发送：{@code send()} 立即返回 {@link CompletableFuture}。
 * <p>示例：{@code KafkaUtils.async().topic("t").payload(msg).send()}</p>
 *
 * @author zwei
 */
public final class KafkaAsyncSender extends KafkaSendBuilderBase<KafkaAsyncSender> {

    /** 链式 API 返回自身 */
    @Override
    protected KafkaAsyncSender self() {
        return this;
    }

    /**
     * 校验通过后提交发送；提交前本实例即作废，结果通过 Future 获取（Future 失败亦不可再 send）。
     */
    public CompletableFuture<SendResult<String, Object>> send() {
        ensureOpen();
        validateBeforeSend();
        var template = KafkaTemplateHolder.resolve(templateBeanName());
        var record = toProducerRecord();
        terminate();
        return template.send(record);
    }

}
