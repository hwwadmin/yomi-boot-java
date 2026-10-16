package com.yomi.boot.java.component.kafka;

import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 发送入口，依赖 Spring Boot 配置的 {@link KafkaTemplate}。
 * <p>每次发送新建一条链；{@code send()} 在校验通过后即结束 sender（发送成功或失败均不可复用）。</p>
 * <p>同步/异步 send 的阻塞与异常边界（元数据不可用时最长阻塞 max.block.ms）见 {@link KafkaSyncSender} / {@link KafkaAsyncSender}。</p>
 *
 * @author zwei
 */
public final class KafkaUtils {

    private KafkaUtils() {
    }

    /** 同步发送构建器 */
    public static KafkaSyncSender sync() {
        return new KafkaSyncSender();
    }

    /** 异步发送构建器 */
    public static KafkaAsyncSender async() {
        return new KafkaAsyncSender();
    }

}
