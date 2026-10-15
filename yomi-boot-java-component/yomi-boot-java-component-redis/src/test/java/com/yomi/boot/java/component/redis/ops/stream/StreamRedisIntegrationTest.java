package com.yomi.boot.java.component.redis.ops.stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Redis 上的 Stream 消费：失败消息保留在 PEL，
 * 并可通过 {@link StreamRedisSupport#claimAndRedeliver} 认领重投、重投成功后出 PEL。
 * <p>默认跳过；配置 {@code yomi.test.redis.host}（或环境变量 YOMI_REDIS_HOST 等）后执行</p>
 */
@EnabledIf("redisConfigured")
class StreamRedisIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;
    private static StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private static StreamRedisSupport support;

    static boolean redisConfigured() {
        return RedisTestSettings.configured();
    }

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(RedisTestSettings.host(), RedisTestSettings.port());
        if (org.apache.commons.lang3.StringUtils.isNotBlank(RedisTestSettings.password())) {
            configuration.setPassword(RedisPassword.of(RedisTestSettings.password()));
        }
        configuration.setDatabase(RedisTestSettings.database());
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        container = StreamMessageListenerContainer.create(connectionFactory,
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofMillis(200))
                        .batchSize(10)
                        .build());
        container.start();
        support = new StreamRedisSupport(template, container);
    }

    @AfterAll
    static void tearDown() {
        if (container != null) {
            container.stop();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void failedMessageStaysPendingAndIsRedeliveredByClaim() throws Exception {
        String stream = "yomi:test:stream:" + UUID.randomUUID();
        String group = "grp";
        List<String> handled = new CopyOnWriteArrayList<>();

        // 先 listen（建组取 latest）再发消息
        support.listen(stream, group, "c-1", (key, id, body) -> {
            if ("fail".equals(body.get(StreamRedisSupport.PAYLOAD_FIELD))) {
                throw new IllegalStateException("模拟业务处理失败");
            }
            handled.add(id);
        });
        String okId = support.add(stream, "ok");
        String failId = support.add(stream, "fail");

        try {
            // 成功消息在 PEL 外，失败消息留在 PEL
            assertTrue(awaitCondition(() -> handled.contains(okId)), "成功消息应被处理");
            assertTrue(awaitCondition(() -> !claimedPending(stream, group, failId).isEmpty()),
                    "处理失败的消息必须留在 PEL（NOACK 时代会直接丢失）");
            assertTrue(awaitCondition(() -> claimedPending(stream, group, okId).isEmpty()),
                    "处理成功的消息应已 ACK 且不在 PEL 中");

            // 认领重投：这次 handler 处理成功 → 出 PEL
            int processed = support.claimAndRedeliver(stream, group, "c-recover", Duration.ZERO,
                    (key, id, body) -> handled.add(id));
            assertEquals(1, processed, "应认领到 1 条超时未确认的消息");
            assertTrue(handled.contains(failId), "重投必须再次交给 handler");
            assertTrue(awaitCondition(() -> claimedPending(stream, group, failId).isEmpty()),
                    "重投成功后消息必须离开 PEL");
        } finally {
            template.delete(stream);
        }
    }

    /** 断言存在已认领的 pending 消息 */
    private static List<String> claimedPending(String stream, String group, String id) {
        PendingMessages pending = template.opsForStream().pending(stream, group, Range.unbounded(), 100);
        return pending.stream().map(m -> m.getId().getValue()).filter(pendingId -> pendingId.equals(id)).toList();
    }

    /** 等待 Stream 条件满足 */
    private static boolean awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

}
