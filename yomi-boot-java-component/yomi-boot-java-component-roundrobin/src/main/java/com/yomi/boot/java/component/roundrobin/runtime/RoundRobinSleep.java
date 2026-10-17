package com.yomi.boot.java.component.roundrobin.runtime;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * 可中断休眠
 *
 * @author zwei
 */
public final class RoundRobinSleep {

    /** 分片 park 上限，保证取消能在 50ms 内被观察到 */
    private static final long MAX_PARK_NANOS = 50_000_000L;

    private RoundRobinSleep() {
    }

    /**
     * 休眠指定时长；取消令牌被取消时提前返回 false
     * <p>
     * 休眠前暂存并清除线程中断标志，返回前恢复；中断标志残留会让 {@link LockSupport#parkNanos} 立即返回并忙等。
     */
    public static boolean sleep(CancellationToken token, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return !token.isCancelled();
        }
        long deadline = System.nanoTime() + duration.toNanos();
        boolean interrupted = Thread.interrupted();
        try {
            while (!token.isCancelled()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return true;
                }
                LockSupport.parkNanos(Math.min(remaining, MAX_PARK_NANOS));
                if (Thread.interrupted()) {
                    // park 期间被中断会立刻返回；标志不清掉的话后面每次 park 都会立刻返回，退化成自旋
                    interrupted = true;
                }
            }
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
