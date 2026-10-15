package com.yomi.boot.java.component.redis.config;

import com.yomi.boot.java.component.redis.ops.extension.RedisRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 限流器配置
 * <p>
 * {@code yomi.ratelimit} 同时承担开关与限流键前缀：属性存在且值不为 false 时启用，
 * 属性值作为限流键前缀，例如 {@code yomi.ratelimit: myapp:ratelimit:}。
 * 多应用共用 Redis 时建议设置包含应用名的前缀
 *
 * @author zwei
 */
@Configuration
@ConditionalOnProperty(value = "yomi.ratelimit")
public class RateLimitConfig {

    /**
     * 限流键前缀，多应用共用 Redis 时建议设置为包含应用名的前缀
     */
    @Value("${yomi.ratelimit:}")
    private String rateLimiterKeyPrefix;

    /**
     * 默认限流器，下游应用可自定义 {@link RedisRateLimiter} Bean 覆盖
     */
    @Bean
    @ConditionalOnMissingBean(RedisRateLimiter.class)
    public RedisRateLimiter redisRateLimiter(StringRedisTemplate stringRedisTemplate) {
        return new RedisRateLimiter(rateLimiterKeyPrefix, stringRedisTemplate);
    }

}
