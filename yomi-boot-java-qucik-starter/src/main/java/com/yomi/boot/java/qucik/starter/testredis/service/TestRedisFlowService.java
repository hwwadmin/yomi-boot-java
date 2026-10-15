package com.yomi.boot.java.qucik.starter.testredis.service;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.component.redis.LockUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis 组件流程：分布式锁 + {@link RequestLimit}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestRedisFlowService {

    /** 演示用限流服务 */
    private final TestRedisRateLimitService rateLimitService;

    /** 执行限流与缓存等演示 */
    public List<String> runFlow() {
        String flowId = UUID.randomUUID().toString();
        List<String> steps = new ArrayList<>();
        try {
            steps.add(runLockSteps(flowId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("锁演示被中断", e);
        }
        steps.add(runRateLimitSteps(flowId));
        steps.add("3.流程通过");
        return steps;
    }

    /** 演示分布式锁流程 */
    private String runLockSteps(String flowId) throws InterruptedException {
        String lockKey = "yomi:test:redis:lock:" + flowId;
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicReference<Throwable> holderError = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            try {
                LockUtils.tryLock(lockKey, () -> {
                    holderReady.countDown();
                    try {
                        releaseHolder.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }, "演示持锁线程");
            } catch (Throwable t) {
                holderError.set(t);
                holderReady.countDown();
            }
        }, "test-redis-lock-holder");
        holder.setDaemon(true);
        holder.start();

        Assert.isTrue(holderReady.await(10, TimeUnit.SECONDS), "持锁线程未在时限内抢到锁");
        Assert.isNull(holderError.get(), "持锁线程异常: {}", holderError.get());
        try {
            try {
                LockUtils.tryLock(lockKey, () -> 1, "演示二次抢锁");
                throw new IllegalStateException("另一线程持锁时 tryLock 应失败");
            } catch (Exception e) {
                Assert.isTrue(LockUtils.isLockBusy(e), "应为 LockBusyException, 实际={}", e.toString());
            }
            return "1.分布式锁: 另一线程持锁时 tryLock 被拒绝";
        } finally {
            releaseHolder.countDown();
            holder.join(10_000);
        }
    }

    /** 演示限流流程 */
    private String runRateLimitSteps(String flowId) {
        String limitKey = "yomi:test:redis:limit:" + flowId;
        rateLimitService.ping(limitKey);
        try {
            rateLimitService.ping(limitKey);
            throw new IllegalStateException("第二次调用应被限流");
        } catch (BizException rateLimited) {
            Assert.isTrue(rateLimited.getMessage().contains("限流"),
                    "限流消息不符: {}", rateLimited.getMessage());
            return "2.请求限流: 同 key 连续两次, 第二次被拒绝";
        }
    }

}
