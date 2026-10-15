package com.yomi.boot.java.qucik.starter.testroundrobin.worker;

import com.yomi.boot.java.component.redis.RedisUtils;
import com.yomi.boot.java.component.roundrobin.annotation.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinContext;
import com.yomi.boot.java.qucik.starter.testroundrobin.TestRoundRobinDemoKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 声明式 {@link RoundRobinTask} 演示。
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
public class TestRoundRobinDemoWorker {

    @RoundRobinTask(name = "quick-starter-demo-tick", description = "演示 tick：递增 Redis 计数器", frequency = "500ms")
    public void tick(RoundRobinContext ctx) {
        Long value = RedisUtils.stringRedisTemplate().opsForValue().increment(TestRoundRobinDemoKeys.TICK_COUNTER);
        log.debug("[RoundRobin测试] tick count={}", value);
    }

}
