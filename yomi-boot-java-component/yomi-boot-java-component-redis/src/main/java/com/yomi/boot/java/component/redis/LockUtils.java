package com.yomi.boot.java.component.redis;

import com.yomi.boot.java.base.exception.UtilsException;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.ExUtils;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 锁工具类
 *
 * @author zwei
 */
@Slf4j
public abstract class LockUtils {

    /**
     * 可重入锁
     */
    public static Lock ofReentrant(String lockKey) {
        // 基于redisson创建
        return RedisUtils.redisson().getLock(lockKey);
    }

    /**
     * 公平锁
     */
    public static Lock ofFair(String lockKey) {
        // 基于redisson创建
        return RedisUtils.redisson().getFairLock(lockKey);
    }

    /**
     * 获取锁
     * 会循环不断的尝试获取锁直至获取到锁并执行业务逻辑
     * 注意：持锁时长不受限（watchdog 自动续期），持有方挂死会导致锁长期无法释放，请求路径建议使用 tryLock
     */
    public static <T> T lock(String lockKey, Supplier<T> supplier, String exceptionMsg) {
        return execute(ofReentrant(lockKey), Lock::lock, supplier, exceptionMsg);
    }

    /**
     * 获取锁
     * 只会获取一次锁，这次没拿到就失败
     * 注意：持锁时长不受限（watchdog 自动续期）
     */
    public static <T> T tryLock(String lockKey, Supplier<T> supplier, String exceptionMsg) {
        return execute(ofReentrant(lockKey), lock -> {
            if (!lock.tryLock()) {
                throw new LockBusyException(lockKey);
            }
        }, supplier, exceptionMsg);
    }

    /**
     * 获取锁
     * 在等待时间内尝试获取锁，直到获取到锁或者超时
     * 注意：waitTime 是获取锁的等待时长，不是持锁时长；持锁时长不受限（watchdog 自动续期），
     * 需要限定持锁时长（持有方挂死时锁自动释放）时使用 {@link #tryLockWithLease}
     */
    public static <T> T tryLock(String lockKey, long waitTime, TimeUnit timeUnit, Supplier<T> supplier,
                                String exceptionMsg) {
        return execute(ofReentrant(lockKey), lock -> {
            boolean result = false;
            try {
                result = lock.tryLock(waitTime, timeUnit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!result) {
                throw new LockBusyException(lockKey);
            }
        }, supplier, exceptionMsg);
    }

    /**
     * 获取锁（限定持锁时长）
     * 在等待时间内尝试获取锁，获取成功后持锁 leaseTime 自动释放，不依赖 watchdog 续期；
     * 持有方挂死时锁也会在 leaseTime 后自动释放
     * 注意：业务执行时长超过 leaseTime 时锁会提前释放，需自行评估业务耗时
     */
    public static <T> T tryLockWithLease(String lockKey, long waitTime, long leaseTime, TimeUnit unit,
                                         Supplier<T> supplier, String exceptionMsg) {
        return execute(ofReentrant(lockKey), lock -> {
            boolean result = false;
            try {
                result = ((RLock) lock).tryLock(waitTime, leaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!result) {
                throw new LockBusyException(lockKey);
            }
        }, supplier, exceptionMsg);
    }

    /**
     * 判断是否为未获取到锁的异常
     *
     * @author zwei
     */
    public static boolean isLockBusy(Throwable throwable) {
        return ExUtils.isCausedBy(throwable, LockBusyException.class);
    }

    /**
     * 执行锁对应的业务逻辑
     */
    public static <T> T execute(Lock lock, Consumer<Lock> action, Supplier<T> supplier, String exceptionMsg) {
        Assert.nonNull(lock, "lock must be not null");
        Assert.nonNull(action, "action must be not null");
        Assert.nonNull(supplier, "supplier must be not null");
        Assert.nonNull(exceptionMsg, "exceptionMsg must be not null");

        boolean locked = false;
        try {
            action.accept(lock);
            locked = true;
        } catch (final Exception e) {
            if (ExUtils.isCausedBySystem(e)) throw e; // 锁忙等业务异常原样抛出
            throw UtilsException.exception(e, exceptionMsg);
        }
        try {
            return supplier.get();
        } finally {
            if (locked) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    // 锁可能已过期或被其他实例持有，Redis 也可能不可达；锁到期自动释放，
                    // 释放失败只记日志，业务异常照常抛出
                    log.warn("分布式锁释放失败，可能已过期或易主", e);
                }
            }
        }
    }

    private LockUtils() {

    }

}
