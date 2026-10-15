package com.yomi.boot.java.qucik.starter.testredis.service;

import com.yomi.boot.java.component.redis.annotation.RequestLimit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 独立 Bean，保证 {@link RequestLimit} 切面生效。
 */
@Slf4j
@Service
public class TestRedisRateLimitService {

    @RequestLimit(key = "#limitKey", replenishRate = 1, burstCapacity = 1)
    public void ping(String limitKey) {
        log.debug("[Redis测试] rate limit ping key={}", limitKey);
    }

}
