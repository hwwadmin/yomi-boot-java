package com.yomi.boot.java.component.kafka;

import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

/**
 * 异步发送：提交后不等待 broker 确认，结果通过 {@link CompletableFuture} 获取。
 * <p>示例：{@code KafkaUtils.async().topic("t").payload(msg).send()}</p>
 * <p>"立即返回"只对元数据就绪的场景成立：元数据不可用时 {@code send()} 会在客户端同步阻塞最长
 * max.block.ms（默认 60 秒），并直接抛 Spring 的 KafkaException，调用方需自行捕获。</p>
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
     * 校验通过后提交发送；调用即作废本实例（Future 失败同样作废），结果通过 Future 获取。
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
