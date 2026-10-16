package com.yomi.boot.java.component.roundrobin.config;

import com.yomi.boot.java.component.redis.config.RedisAutoConfiguration;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import com.yomi.boot.java.component.roundrobin.spring.RoundRobinLifecycle;
import com.yomi.boot.java.component.roundrobin.spring.RoundRobinTaskBeanPostProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * 分布式轮询调度自动装配，{@code yomi.roundrobin.enabled=true} 时注册任务扫描与 {@link com.yomi.boot.java.component.roundrobin.spring.RoundRobinLifecycle}
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
        log.info("轮询调度已启用: storePrefix={}", prefix);
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
     * 专用调度线程池（承载 50ms 周期的锁存活检测与 max-hold）。
     * <p>只有宿主提供<b>名为 {@code roundRobinScheduler}、且类型为 {@link ScheduledExecutorService}</b> 的 Bean 才会顶替内置池
     * （普通的同类型 Bean 不再被静默接管）；池太小或 Redis 变慢会让检测/max-hold 延迟，检测延迟会被量出来并按次告警。
     * 同名但类型不符的 Bean 会让 {@link RoundRobinLifecycle} 在启动期注入失败（明确报错，不静默降级）</p>
     */
    @Bean(name = "roundRobinScheduler", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "roundRobinScheduler")
    public ScheduledExecutorService roundRobinScheduler() {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("yomi-roundrobin-shared-scheduler");
            return t;
        };
        return Executors.newScheduledThreadPool(4, factory);
    }

    @Bean
    public RoundRobinTaskBeanPostProcessor roundRobinTaskBeanPostProcessor(RoundRobinRegistry registry) {
        return new RoundRobinTaskBeanPostProcessor(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RoundRobinLifecycle roundRobinLifecycle(RoundRobinRegistry registry,
                                                   RoundRobinLockClient lockClient,
                                                   @Qualifier("roundRobinScheduler") ScheduledExecutorService roundRobinScheduler) {
        return new RoundRobinLifecycle(registry, lockClient, roundRobinScheduler);
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
