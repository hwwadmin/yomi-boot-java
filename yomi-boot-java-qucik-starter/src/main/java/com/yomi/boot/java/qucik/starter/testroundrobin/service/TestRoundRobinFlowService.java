package com.yomi.boot.java.qucik.starter.testroundrobin.service;

import com.yomi.boot.java.base.function.SpinBarrier;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.component.redis.RedisUtils;
import com.yomi.boot.java.qucik.starter.testroundrobin.TestRoundRobinDemoKeys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RoundRobin 流程：重置计数后等待 {@link com.yomi.boot.java.qucik.starter.testroundrobin.worker.TestRoundRobinDemoWorker} 递增。
 */
@Service
@ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
public class TestRoundRobinFlowService {

    /** 演示：等待毫秒数 */
    private static final long WAIT_MS = 3_000L;

    /** 触发一次演示轮询任务 */
    public List<String> runFlow() {
        List<String> steps = new ArrayList<>();
        RedisUtils.stringRedisTemplate().opsForValue().set(TestRoundRobinDemoKeys.TICK_COUNTER, "0");
        steps.add("1.计数重置: key=" + TestRoundRobinDemoKeys.TICK_COUNTER + ", value=0");

        boolean ticked = SpinBarrier.spin(() -> readCount() >= 1, WAIT_MS);
        long count = readCount();
        Assert.isTrue(ticked && count >= 1, "等待 @RoundRobinTask 递增超时, count={}", count);
        steps.add("2.轮询任务: count=" + count + " (>=1)");

        steps.add("3.流程通过");
        return steps;
    }

    /** 读取 RoundRobin 演示计数 */
    private long readCount() {
        String raw = RedisUtils.stringRedisTemplate().opsForValue().get(TestRoundRobinDemoKeys.TICK_COUNTER);
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

}
