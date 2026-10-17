package com.yomi.boot.java.component.roundrobin.config;

import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import com.yomi.boot.java.component.roundrobin.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 分布式轮询调度自动装配，{@code yomi.roundrobin.enabled=true} 时注册任务注册表与 {@link RoundRobinLifecycle}
 *
 * @author zwei
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(RoundRobinProperties.class)
@ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
public class RoundRobinAutoConfiguration {

    @Bean
    public RoundRobinKeys roundRobinKeys(RoundRobinProperties properties, Environment environment) {
        String prefix = resolvePrefix(properties, environment);
        log.info("[RoundRobin]轮询调度组件已启用: storePrefix={}", prefix);
        return new RoundRobinKeys(prefix);
    }

    @Bean
    @ConditionalOnMissingBean
    public RoundRobinLockClient roundRobinLockClient(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                                     ObjectProvider<RedissonClient> redissonClientProvider,
                                                     RoundRobinKeys keys) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redisTemplate == null || redissonClient == null) {
            throw new IllegalStateException(
                    "yomi.roundrobin.enabled=true 需要 Redis：请配置 spring.data.redis.host 以启用 redis 组件");
        }
        return new RoundRobinLockClient(redisTemplate, redissonClient, keys);
    }

    @Bean
    @ConditionalOnMissingBean
    public RoundRobinRegistry roundRobinRegistry() {
        return new RoundRobinRegistry();
    }

    /**
     * 生命周期：启动/停止所有任务。锁存活探测与 max-hold 由每轮一条虚拟线程承载（{@code RoundRobinTick} 内部），
     * 组件不再需要调度线程池，也没有宿主要调的池参数
     */
    @Bean
    @ConditionalOnMissingBean
    public RoundRobinLifecycle roundRobinLifecycle(RoundRobinRegistry registry,
                                                   RoundRobinLockClient lockClient) {
        return new RoundRobinLifecycle(registry, lockClient);
    }

    /** 从环境补全存储键前缀 */
    private static String resolvePrefix(RoundRobinProperties properties, Environment environment) {
        String raw = properties.getStorePrefix();
        if (StringUtils.isBlank(raw)) {
            String applicationName = environment.getProperty("spring.application.name");
            if (StringUtils.isBlank(applicationName)) {
                throw new IllegalStateException(
                        "yomi.roundrobin.store-prefix 未配置，且无法从 spring.application.name 推导，请显式配置");
            }
            raw = "yomi:roundrobin:" + applicationName;
        }
        return raw;
    }

}
