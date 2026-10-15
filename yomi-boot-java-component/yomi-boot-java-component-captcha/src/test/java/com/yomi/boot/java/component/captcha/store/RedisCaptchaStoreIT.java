package com.yomi.boot.java.component.captcha.store;

import com.yomi.boot.java.component.captcha.core.CaptchaRandoms;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 存储集成测试：真实 Redis，验证键前缀、TTL 与 GETDEL 一次性消费
 * <p>
 * 未配置 {@code yomi.test.redis.host} / {@code YOMI_REDIS_HOST} 时整体跳过。
 *
 * @author zwei
 */
@EnabledIf("redisConfigured")
class RedisCaptchaStoreIT {

    private static final Duration TTL = Duration.ofSeconds(30);

    private static LettuceConnectionFactory connectionFactory;

    private static StringRedisTemplate redisTemplate;

    private static CaptchaKeys keys;

    private static RedisCaptchaStore store;

    static boolean redisConfigured() {
        return RedisTestSettings.configured();
    }

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                RedisTestSettings.host(), RedisTestSettings.port());
        if (StringUtils.isNotBlank(RedisTestSettings.password())) {
            configuration.setPassword(RedisPassword.of(RedisTestSettings.password()));
        }
        configuration.setDatabase(RedisTestSettings.database());
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        keys = new CaptchaKeys("yomi:captcha:it");
        store = new RedisCaptchaStore(redisTemplate, keys, TTL);
    }

    @AfterAll
    static void tearDown() {
        if (redisTemplate != null) {
            Set<String> leftovers = redisTemplate.keys(keys.cleanupKeyPattern());
            if (leftovers != null && !leftovers.isEmpty()) {
                redisTemplate.delete(leftovers);
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void saveSetsTtlAndConsumeDeletesKey() {
        String captchaId = CaptchaRandoms.nextId();
        store.save(captchaId, "AB12");
        String key = keys.challengeKey(captchaId);
        Long ttl = redisTemplate.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= TTL.toSeconds(), "TTL 应为挑战有效期: " + ttl);

        assertEquals("AB12", store.remove(captchaId));
        assertNull(store.remove(captchaId), "GETDEL 一次性消费");
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(key)), "消费后键应删除");
        assertEquals("redis", store.name());
    }

}
