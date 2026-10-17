package com.yomi.boot.java.component.redis.ops.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRateLimiterTest {

    /**
     * ttl 至少为 1 秒，保证令牌桶状态写回 Redis；
     * 合法参数组合得到的 ttl 均不小于 1 秒
     */
    @Test
    void ttlNeverDropsBelowOneSecond() {
        for (int rate = 1; rate <= 200; rate++) {
            for (int capacity = 1; capacity <= 200; capacity++) {
                int actualRate = rate;
                int actualCapacity = capacity;
                int ttl = RedisRateLimiter.ttlSeconds(actualRate, actualCapacity);
                assertTrue(ttl >= 1, () -> "rate=" + actualRate + ", capacity=" + actualCapacity + ", ttl=" + ttl);
            }
        }
    }

    @Test
    void ttlKeepsTwoFillTimesWhenRepresentable() {
        assertEquals(2, RedisRateLimiter.ttlSeconds(1, 1));
        assertEquals(5, RedisRateLimiter.ttlSeconds(2, 5));
        assertEquals(1, RedisRateLimiter.ttlSeconds(10, 5));
    }

    /** 高补充速率 + 小桶容量（如 100 qps、不允许突发）时取 1 秒下限 */
    @Test
    void ttlFallsBackToOneSecondForHighRateSmallBurst() {
        assertEquals(1, RedisRateLimiter.ttlSeconds(100, 10));
        assertEquals(1, RedisRateLimiter.ttlSeconds(5, 1));
        assertEquals(1, RedisRateLimiter.ttlSeconds(3, 1));
    }
}
