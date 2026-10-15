package com.yomi.boot.java.component.roundrobin.runtime;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundRobinSleepTest {

    private static final Duration SLEEP = Duration.ofMillis(300);

    /** 读取当前线程 CPU 时间（毫秒） */
    private static long currentThreadCpuMillis() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1_000_000L;
    }

    @Test
    void sleepsFullDuration() {
        long start = System.nanoTime();

        assertTrue(RoundRobinSleep.sleep(new CancellationToken(), SLEEP));

        assertTrue(System.nanoTime() - start >= SLEEP.toNanos());
    }

    @Test
    void returnsFalseImmediatelyWhenCancelled() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        long start = System.nanoTime();

        assertFalse(RoundRobinSleep.sleep(token, Duration.ofSeconds(30)));

        assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos(), "取消后应立即返回");
    }

    /**
     * 线程中断标志置位时，休眠应正常结束且不应忙等
     * （实测：400ms 休眠消耗 390ms CPU，对照 0ms）
     */
    @Test
    void doesNotSpinWhenInterruptFlagIsSet() {
        Thread.currentThread().interrupt();
        long cpuBefore = currentThreadCpuMillis();
        long start = System.nanoTime();

        boolean slept = RoundRobinSleep.sleep(new CancellationToken(), SLEEP);

        long wallMillis = (System.nanoTime() - start) / 1_000_000L;
        long cpuMillis = currentThreadCpuMillis() - cpuBefore;
        boolean flagRestored = Thread.interrupted();

        assertTrue(slept);
        assertTrue(wallMillis >= SLEEP.toMillis(), "应睡满时长，实际 " + wallMillis + "ms");
        assertTrue(flagRestored, "中断标志应原样还原给调用方");
        assertTrue(cpuMillis < 100,
                "休眠期间 CPU 占用应较低：墙钟 " + wallMillis + "ms 却消耗 CPU " + cpuMillis + "ms");
    }
}
