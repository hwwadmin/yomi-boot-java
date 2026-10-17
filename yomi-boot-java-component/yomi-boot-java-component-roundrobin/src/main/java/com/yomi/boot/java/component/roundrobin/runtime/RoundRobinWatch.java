package com.yomi.boot.java.component.roundrobin.runtime;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

/**
 * 单轮观测器：任务持锁执行期间，每 {@link #INTERVAL} 在独立虚拟线程上回调一次
 * <p>
 * 生命周期与轮次一致——{@link RoundRobinTick} 拿到执行锁后启动、finally 中停止。观测线程与业务线程分离，
 * 业务线程阻塞在 handler 里也能被观测；{@link #stop()} 非阻塞（不等待在途回调），与
 * "探测落在 unlock 之后要靠 taskRunning 标志识别"的既有竞态处理配套。
 *
 * @author zwei
 */
@FunctionalInterface
public interface RoundRobinWatch {

    /** 回调周期（也是丢锁发现的间隔上限） */
    Duration INTERVAL = Duration.ofMillis(50);

    /** 停止观测（非阻塞；可重复调用） */
    void stop();

    /**
     * 启动一轮观测（虚拟线程，命名 {@code roundrobin-watch-<任务名>} 便于 jstack 识别）
     *
     * @param taskName 任务名，仅用于线程命名
     * @param onTick   每次到期的回调，参数为与上次回调的纳秒间隔（本轮首次为 -1）。
     *                 回调不得抛出——调用方的观测逻辑按"首条告警 + 恢复补一条"自行兜底
     */
    static RoundRobinWatch start(String taskName, LongConsumer onTick) {
        long periodNanos = INTERVAL.toNanos();
        AtomicBoolean stopped = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().name("roundrobin-watch-" + taskName).unstarted(() -> {
            long nextTickNanos = System.nanoTime() + periodNanos;
            long lastTickNanos = 0L;
            while (!stopped.get()) {
                parkUntil(nextTickNanos, stopped);
                if (stopped.get()) {
                    return;
                }
                long nowNanos = System.nanoTime();
                // 间隔基准在轮内：本轮首次回调没有间隔可算，取 -1 由调用方跳过
                // （不复用上一轮的时点——否则轮间空闲会被算成"探测延迟"）
                long gapNanos = lastTickNanos == 0L ? -1L : nowNanos - lastTickNanos;
                lastTickNanos = nowNanos;
                onTick.accept(gapNanos);
                // 固定节奏；探测被拖住超过一个周期时不追帧，从当前时刻重新起算
                nextTickNanos += periodNanos;
                if (nextTickNanos < System.nanoTime()) {
                    nextTickNanos = System.nanoTime() + periodNanos;
                }
            }
        });
        thread.start();
        return () -> {
            if (stopped.compareAndSet(false, true)) {
                LockSupport.unpark(thread);     // 结束当前 park，尽快退出
            }
        };
    }

    /** park 到目标时刻（容忍伪唤醒）；stop() 置位后立即返回 */
    private static void parkUntil(long deadlineNanos, AtomicBoolean stopped) {
        while (!stopped.get()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return;
            }
            LockSupport.parkNanos(remainingNanos);
        }
    }

}
