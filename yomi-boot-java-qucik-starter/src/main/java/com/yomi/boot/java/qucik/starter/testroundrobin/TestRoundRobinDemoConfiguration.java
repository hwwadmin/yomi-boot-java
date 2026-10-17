package com.yomi.boot.java.qucik.starter.testroundrobin;

import com.yomi.boot.java.component.redis.RedisUtils;
import com.yomi.boot.java.component.roundrobin.RoundRobin;
import com.yomi.boot.java.component.roundrobin.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.RoundRobinTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 编程式注册轮询任务演示：{@link RoundRobin#task} 一行创建，交给 {@link RoundRobinRegistry} 注册。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
public class TestRoundRobinDemoConfiguration {

    /** 演示任务名 */
    public static final String TASK_NAME = "quickStarterDemoTick";

    @Bean
    public RoundRobinTask testRoundRobinDemoTick(RoundRobinRegistry registry) {
        return registry.register(RoundRobin.task(TASK_NAME,
                "演示 tick：递增 Redis 计数器", Duration.ofMillis(500), ctx -> {
                    Long value = RedisUtils.stringRedisTemplate().opsForValue()
                            .increment(TestRoundRobinDemoKeys.TICK_COUNTER);
                    log.debug("[RoundRobin测试] tick count={}", value);
                }));
    }

}
