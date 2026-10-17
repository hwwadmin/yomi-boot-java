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
 * 分布式轮询调度自动装配，{@code yomi.roundrobin.enabled=true} 时装配任务注册表（自带启停：{@link RoundRobinRegistry}）
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

    /** 任务注册表：注册任务并持有各自的调度实例，本身即 SmartLifecycle（启动/停机时机见类注释） */
    @Bean
    @ConditionalOnMissingBean
    public RoundRobinRegistry roundRobinRegistry(RoundRobinLockClient lockClient) {
        return new RoundRobinRegistry(lockClient);
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
