package com.yomi.boot.java.component.roundrobin.lock;

import com.yomi.boot.java.component.roundrobin.config.RoundRobinKeys;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 频率锁（Redis SET NX）与执行锁（Redisson）
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class RoundRobinLockClient {

    /** 频率锁 SET NX */
    private final StringRedisTemplate stringRedisTemplate;

    /** 执行锁 */
    private final RedissonClient redissonClient;

    /** Redis 键名 */
    private final RoundRobinKeys keys;

    /**
     * 获取频率锁，TTL 等于 frequency
     */
    public boolean tryAcquireFrequency(RoundRobinConfig config) {
        String key = keys.frequencyKey(config.getName());
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", config.getFrequency());
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 尝试获取执行锁。
     * <p>
     * execWatchdog=true 时使用 Redisson 看门狗续期，exec-lock-ttl 仅用于与 max-hold 校验；
     * execWatchdog=false 时使用固定租约 exec-lock-ttl。
     */
    public RLock tryAcquireExecution(RoundRobinConfig config) throws InterruptedException {
        RLock lock = redissonClient.getLock(keys.lockKey(config.getName()));
        long waitMillis = config.getLockWait().toMillis();
        boolean held;
        if (config.isExecWatchdog()) {
            if (waitMillis <= 0) {
                held = lock.tryLock();
            } else {
                held = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            }
        } else {
            long leaseMillis = config.getExecLockTtl().toMillis();
            if (waitMillis <= 0) {
                held = lock.tryLock(0, leaseMillis, TimeUnit.MILLISECONDS);
            } else {
                held = lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
            }
        }
        return held ? lock : null;
    }

    /** 解锁结果 */
    public enum UnlockOutcome {
        /** 正常释放（含重入计数未归零） */
        RELEASED,
        /** 本线程的锁字段已不在锁里：锁已过期、被删或已被其它实例接管 */
        NOT_HELD,
        /** 释放命令失败（网络/超时），锁与续期可能仍在 */
        FAILED
    }

    /**
     * 释放执行锁。
     * <p>
     * 这里直接调用 unlock，不用 {@code isHeldByCurrentThread()} 预判归属：预判多一次 Redis
     * 往返，它失败时 unlock 会被整段跳过，而 Redisson 只在 unlock 流程里取消看门狗续期——锁被持续
     * 续期，本进程重启前其它实例无法接管该任务。
     * unlock 已在 Lua 里校验归属，非持有者只会抛 {@link IllegalMonitorStateException}，不会误删他人的锁。
     *
     * @return {@link UnlockOutcome#NOT_HELD} 是丢锁的确凿信号（本线程字段已不在锁里），由调用方据此上报
     */
    public UnlockOutcome unlockQuietly(RoundRobinConfig config, RLock lock) {
        if (lock == null) {
            return UnlockOutcome.RELEASED;
        }
        try {
            lock.unlock();
            return UnlockOutcome.RELEASED;
        } catch (IllegalMonitorStateException e) {
            // 字段已不在锁里：解锁前锁就已过期/被删/易主
            return UnlockOutcome.NOT_HELD;
        } catch (Exception e) {
            // Redisson 命令层已按 retryAttempts 重试过；走到这里说明重试也没成功
            log.warn("RoundRobin[{}] 执行锁释放失败；看门狗模式下续期不会停止，"
                    + "该任务在本进程重启前可能无法被其它实例接管", config.getName(), e);
            return UnlockOutcome.FAILED;
        }
    }

    /**
     * 由调度线程检测 worker 是否仍持有执行锁（{@code isHeldByCurrentThread} 只在 worker 线程内有意义）
     */
    public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
        return lock != null && lock.isHeldByThread(workerThreadId);
    }

}
