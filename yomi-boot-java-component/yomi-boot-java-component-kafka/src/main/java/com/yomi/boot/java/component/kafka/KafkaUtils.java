package com.yomi.boot.java.component.kafka;

import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 发送入口，依赖 Spring Boot 配置的 {@link KafkaTemplate}。
 * <p>每次发送新建一条链；{@code send()} 在校验通过后即结束 sender（发送成功或失败均不可复用）。</p>
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
