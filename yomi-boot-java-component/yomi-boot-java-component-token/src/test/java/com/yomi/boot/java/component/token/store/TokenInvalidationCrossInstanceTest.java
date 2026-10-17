package com.yomi.boot.java.component.token.store;

import com.yomi.boot.java.component.token.RedisTestSettings;
import com.yomi.boot.java.component.token.session.TokenSession;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 失效通知的真实 Redis 语义（两个"实例"共享一个 Redis、各自订阅同一通道）：
 * <ol>
 *   <li>一个实例撤销后，另一个已订阅的实例条目被通知立即清掉（不等 TTL）；</li>
 *   <li>未订阅的实例按条目 TTL 生效：窗口内仍命中，TTL 到点失效（pub/sub 是"至多一次"）</li>
 * </ol>
 * 默认跳过；配置 {@code yomi.test.redis.host}（或环境变量 YOMI_REDIS_HOST 等）后执行
 *
 * @author zwei
 */
@EnabledIf("redisConfigured")
class TokenInvalidationCrossInstanceTest {

    private static final TokenKeys KEYS = new TokenKeys("yomi:test:token-notify:" + UUID.randomUUID());

    private static LettuceConnectionFactory connectionFactory;

    private static StringRedisTemplate redisTemplate;

    static boolean redisConfigured() {
        return RedisTestSettings.configured();
    }

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(RedisTestSettings.host(), RedisTestSettings.port());
        if (StringUtils.isNotBlank(RedisTestSettings.password())) {
            configuration.setPassword(RedisPassword.of(RedisTestSettings.password()));
        }
        configuration.setDatabase(RedisTestSettings.database());
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (redisTemplate != null) {
            for (String pattern : KEYS.cleanupKeyPatterns()) {
                Set<String> created = redisTemplate.keys(pattern);
                if (CollectionUtils.isNotEmpty(created)) {
                    redisTemplate.delete(created);
                }
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void revokeOnOneInstanceInvalidatesSubscribedInstanceEntry() {
        Instance a = new Instance(Duration.ofSeconds(30), true);
        Instance b = new Instance(Duration.ofSeconds(30), true);
        try {
            String token = "tok-" + UUID.randomUUID();
            b.store.save(token, sessionOf(9201L, Duration.ofMinutes(10)));
            assertNotNull(a.load(token), "实例 A 读入进程内缓存");

            b.store.delete(token);

            assertNull(awaitInvalidated(a, token), "通知必须让另一实例的条目立即失效（不等 TTL）");
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void withoutSubscriptionEntryExpiresByTtl() throws InterruptedException {
        Instance quiet = new Instance(Duration.ofMillis(700), false);
        Instance publisher = new Instance(Duration.ofSeconds(30), false);
        try {
            String token = "tok-" + UUID.randomUUID();
            publisher.store.save(token, sessionOf(9202L, Duration.ofMinutes(10)));
            assertNotNull(quiet.load(token));

            publisher.store.delete(token);
            assertNotNull(quiet.load(token), "没订阅通知：条目 TTL 窗口内仍命中（pub/sub 至多一次，这是接受的代价）");

            Thread.sleep(900);
            assertNull(quiet.load(token), "TTL 到点后回源，令牌已删即失效");
        } finally {
            quiet.close();
            publisher.close();
        }
    }

    /** 等待条目被通知清掉（容许毫秒级到达延迟）；仍是命中则返回它 */
    private static TokenSession awaitInvalidated(Instance instance, String token) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        TokenSession last = null;
        while (System.nanoTime() < deadline) {
            last = instance.load(token);
            if (last == null) {
                return null;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    private static TokenSession sessionOf(long userId, Duration ttl) {
        Instant now = Instant.now();
        TokenSession session = new TokenSession();
        session.setUserId(userId);
        session.setIssuedAt(now);
        session.setExpireAt(now.plus(ttl));
        return session;
    }

    /** 一个"实例"：独立缓存 + 独立通道；可选是否订阅通知通道 */
    private static final class Instance implements AutoCloseable {

        private final RedisTokenStore store;

        private final RedisMessageListenerContainer container;

        Instance(Duration localCacheTtl, boolean subscribe) {
            TokenSessionCache cache = new TokenSessionCache(localCacheTtl);
            TokenInvalidationChannel channel =
                    new TokenInvalidationChannel(redisTemplate, KEYS.invalidateChannel(), cache);
            this.store = new RedisTokenStore(redisTemplate, KEYS, cache, channel);
            if (subscribe) {
                RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
                listenerContainer.setConnectionFactory(connectionFactory);
                listenerContainer.addMessageListener(channel, new ChannelTopic(KEYS.invalidateChannel()));
                listenerContainer.afterPropertiesSet();
                listenerContainer.start();
                this.container = listenerContainer;
            } else {
                this.container = null;
            }
        }

        TokenSession load(String token) {
            return store.load(token);
        }

        @Override
        public void close() {
            if (container == null) {
                return;
            }
            try {
                container.stop();
                container.destroy();
            } catch (Exception e) {
                // 测试收尾：容器清理失败不影响断言（destroy 声明了受检异常）
            }
        }
    }
}
