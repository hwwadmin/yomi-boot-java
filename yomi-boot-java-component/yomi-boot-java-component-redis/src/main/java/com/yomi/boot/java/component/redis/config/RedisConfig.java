package com.yomi.boot.java.component.redis.config;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

/**
 * redis 配置
 * <p>
 * 激活条件与组件其余成员一致（{@link RedisConfiguredCondition}）：配置了 {@code spring.data.redis.*}
 * 连接目标（host / url / sentinel / cluster / masterreplica 任一）才装配。仅宿主自建
 * {@link RedisConnectionFactory} 而不配连接目标属性时组件保持不激活——这正是不能按
 * {@code @ConditionalOnBean(RedisConnectionFactory.class)} 激活的原因（Boot 默认 localhost 工厂会误触发）
 *
 * @author zwei
 */
@Configuration
@Conditional(RedisConfiguredCondition.class)
public class RedisConfig {

    /**
     * Jackson 序列化的通用模板（{@code StringRedisTemplate} 由 Boot 自动配置提供；
     * 本 Bean 命名为 commonRedisTemplate，避免与 Boot 的 redisTemplate 重名冲突）
     */
    @Bean
    @ConditionalOnMissingBean
    public CommonRedisTemplate commonRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new CommonRedisTemplate(redisConnectionFactory);
    }

    /**
     * Lettuce 命令超时兜底
     * Lettuce 默认命令超时 60 秒，Redis 抖动时请求线程会长时间阻塞甚至拖垮线程池；
     * 下游未通过 spring.data.redis.timeout（或 redis url 内置 timeout）配置超时时，收敛为 5 秒
     */
    @Bean
    @ConditionalOnClass(name = "io.lettuce.core.RedisClient")
    public LettuceClientConfigurationBuilderCustomizer lettuceCommandTimeoutCustomizer(Environment environment) {
        boolean timeoutConfigured = StringUtils.isNotBlank(environment.getProperty("spring.data.redis.timeout"))
                || StringUtils.contains(environment.getProperty("spring.data.redis.url"), "timeout=");
        if (timeoutConfigured) {
            return builder -> {
            };
        }
        return builder -> builder.commandTimeout(Duration.ofSeconds(5));
    }

}
