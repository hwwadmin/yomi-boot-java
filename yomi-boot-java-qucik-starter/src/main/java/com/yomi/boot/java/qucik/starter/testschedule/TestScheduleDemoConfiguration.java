package com.yomi.boot.java.qucik.starter.testschedule;

import com.yomi.boot.java.component.redis.RedisUtils;
import com.yomi.boot.java.component.redis.schedule.ClusterTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * 集群定时任务演示：{@code new ClusterTask(...)} 一行创建（Environment 由 Spring 注入），随容器启停；
 * 同一时刻集群内只有一个实例执行。
 */
@Slf4j
@Configuration
public class TestScheduleDemoConfiguration {

    /** 演示任务名 */
    public static final String TASK_NAME = "quickStarterDemoTick";

    @Bean
    public ClusterTask testScheduleDemoTick(Environment environment, LifecycleProperties lifecycleProperties) {
        return new ClusterTask(TASK_NAME, Duration.ofMillis(500), environment, lifecycleProperties, () -> {
            Long value = RedisUtils.stringRedisTemplate().opsForValue()
                    .increment(TestScheduleDemoKeys.TICK_COUNTER);
            log.debug("[集群定时任务测试] tick count={}", value);
        });
    }

}
