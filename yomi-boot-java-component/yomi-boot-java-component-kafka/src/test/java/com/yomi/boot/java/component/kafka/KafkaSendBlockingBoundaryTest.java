package com.yomi.boot.java.component.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真客户端（broker 指向死端口）验证 send 的阻塞边界：
 * 元数据等待发生在 {@code get(timeout)} 之前，timeout 不是硬上界；同步 API 归"发送失败"、异步 API 同步抛错。
 */
class KafkaSendBlockingBoundaryTest {

    /** 客户端元数据等待上限；断言取它的一半作为实际耗时的下界 */
    private static final int MAX_BLOCK_MS = 600;

    private DefaultKafkaProducerFactory<String, Object> producerFactory;

    @BeforeEach
    void bindTemplate() throws Exception {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
        configs.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerFactory = new DefaultKafkaProducerFactory<>(configs);

        KafkaTemplateHolder.clearTemplateCache();
        Field field = KafkaTemplateHolder.class.getDeclaredField("defaultKafkaTemplate");
        field.setAccessible(true);
        field.set(null, new KafkaTemplate<>(producerFactory));
    }

    @AfterEach
    void closeTemplate() {
        KafkaTemplateHolder.clearTemplateCache();
        producerFactory.destroy();
    }

    @Test
    void syncTimeoutDoesNotBoundMetadataWait() {
        long start = System.currentTimeMillis();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> KafkaUtils.sync()
                .topic("probe-sync")
                .payload("v")
                .timeout(100, TimeUnit.MILLISECONDS)
                .send());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(ex.getMessage().contains("发送失败"), "元数据超时应归为发送失败: " + ex.getMessage());
        assertTrue(elapsed >= MAX_BLOCK_MS / 2,
                "timeout(100ms) 不应成为阻塞上界，实际耗时 " + elapsed + "ms");
    }

    @Test
    void asyncSendThrowsSynchronouslyWhenMetadataUnavailable() {
        long start = System.currentTimeMillis();
        assertThrows(KafkaException.class, () -> KafkaUtils.async()
                .topic("probe-async")
                .payload("v")
                .send());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= MAX_BLOCK_MS / 2,
                "异步 send 在元数据不可用时应同步阻塞至 max.block.ms，实际耗时 " + elapsed + "ms");
    }

}
