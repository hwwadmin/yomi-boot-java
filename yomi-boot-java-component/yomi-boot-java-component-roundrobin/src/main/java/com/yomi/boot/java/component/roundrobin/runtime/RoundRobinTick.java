package com.yomi.boot.java.component.roundrobin.runtime;

import com.yomi.boot.java.component.roundrobin.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单轮取锁与任务执行
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class RoundRobinTick {

    /** 分布式锁续期间隔 */
    private static final Duration LOCK_CHECK_INTERVAL = Duration.ofMillis(50);

    /** 存活检测延迟告警阈值（10 个检测周期）：超过说明调度池繁忙（慢 Redis 会占满池线程） */
    private static final long WATCH_LAG_WARN_NANOS = LOCK_CHECK_INTERVAL.toNanos() * 10;

    /** 频率锁与执行锁 */
    private final RoundRobinLockClient lockClient;

    /** max-hold 与丢锁检测 */
    private final ScheduledExecutorService scheduler;

    /** 存活检测上次执行时刻（纳秒）；0 表示还没跑过 */
    private final AtomicLong watchLastRunNanos = new AtomicLong();

    /** 存活检测连续失败中（失败只报首条，恢复时补一条） */
    private final AtomicBoolean watchFailing = new AtomicBoolean();

    /** 已就"检测延迟"告警过（同一次拥堵只报一条，间隔回归正常后复位） */
    private final AtomicBoolean watchLagging = new AtomicBoolean();

    /**
     * @return 是否成功持执行锁并运行了任务
     */
    public boolean tick(CancellationToken scheduleToken, RoundRobinTask task) throws Exception {
        if (!lockClient.tryAcquireFrequency(task)) {
            RoundRobinSleep.sleep(scheduleToken, task.getWakeUpInterval());
            return false;
        }

        RLock execLock = lockClient.tryAcquireExecution(task);
        if (execLock == null) {
            RoundRobinSleep.sleep(scheduleToken, task.getWakeUpInterval());
            return false;
        }

        long workerThreadId = Thread.currentThread().threadId();
        DefaultRoundRobinContext runCtx = new DefaultRoundRobinContext(scheduleToken);
        AtomicBoolean lockLost = new AtomicBoolean(false);
        // 任务是否仍在运行：任务返回后的字段缺失必然是本轮自己的 unlock（50ms 检测与 unlock 存在天然竞态）
        AtomicBoolean taskRunning = new AtomicBoolean(true);
        ScheduledFuture<?> maxHoldFuture = null;
        ScheduledFuture<?> lockWatchFuture = null;
        boolean scheduleUnavailable = false;
        try {
            try {
                maxHoldFuture = scheduleMaxHold(task, runCtx);
                lockWatchFuture = scheduleLockWatch(task, execLock, workerThreadId, runCtx, lockLost, taskRunning);
            } catch (Exception e) {
                // 调度线程池不可用（如宿主提供的池被关停）：无法建立丢锁检测就不跑任务，
                // 释放执行锁等下一轮；异常抛到 try 之外会让 execLock 泄漏，
                // 看门狗模式下被永久续期、其它实例无法接管该任务
                log.warn("RoundRobin[{}] 无法在调度线程池上建立 maxHold/丢锁检测，本轮跳过执行（执行锁已释放）",
                        task.getName(), e);
                scheduleUnavailable = true;
            }
            if (!scheduleUnavailable) {
                // 任务异常与 Error 都向上抛出：由 RoundRobinLoop 统一统计连续失败并退避（worker 线程不退出）
                task.getHandler().run(runCtx);
                return true;
            }
        } finally {
            // 必须先于 cancel/unlock：在途的那次检测若落在 unlock 之后，靠它识别"字段是本轮自己删的"
            taskRunning.set(false);
            cancelQuietly(maxHoldFuture);
            cancelQuietly(lockWatchFuture);
            reportLostOnUnlock(task, lockLost, lockClient.unlockQuietly(task, execLock));
        }
        RoundRobinSleep.sleep(scheduleToken, task.getWakeUpInterval());
        return false;
    }

    /** 注册 max-hold 超时取消任务 */
    private ScheduledFuture<?> scheduleMaxHold(RoundRobinTask task, DefaultRoundRobinContext runCtx) {
        Duration maxHold = task.getMaxHoldDuration();
        if (maxHold == null) {
            return null;
        }
        return scheduler.schedule(() -> {
            runCtx.cancel();
            log.warn("RoundRobin[{}] 已达 max-hold-duration，已置取消标志（协作式：handler 不检查 isCancelled 则任务会继续运行，"
                    + "且执行锁仍被续期；请让 handler 轮询 ctx.isCancelled()）",
                    task.getName());
        }, maxHold.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 注册执行锁存活检测任务 */
    private ScheduledFuture<?> scheduleLockWatch(RoundRobinTask task, RLock lock, long workerThreadId,
                                                 DefaultRoundRobinContext runCtx, AtomicBoolean lockLost,
                                                 AtomicBoolean taskRunning) {
        long periodMs = LOCK_CHECK_INTERVAL.toMillis();
        return scheduler.scheduleAtFixedRate(() -> {
            long nowNanos = System.nanoTime();
            long lastNanos = watchLastRunNanos.getAndSet(nowNanos);
            // 首次执行（lastNanos=0）没有间隔可算，取 -1 交给 reportWatchLag 跳过
            long gapNanos = lastNanos == 0 ? -1 : nowNanos - lastNanos;
            try {
                // 顺序要求：先探活、后读任务状态。任务返回后才出现的字段缺失是本轮 unlock 自己删的，
                // 先读状态的话，那一跳（检测的 Redis 往返落在 unlock 之后）仍会误报丢锁
                boolean held = lockClient.isHeldByWorkerThread(lock, workerThreadId);
                if (!held && taskRunning.get() && lockLost.compareAndSet(false, true)) {
                    logLockLost(task);
                    runCtx.cancel();
                }
                reportWatchHealthy(task);
                reportWatchLag(task, gapNanos);
            } catch (Exception e) {
                // 定时任务内捕获异常并打日志，保证后续周期照常执行；持续故障只报首条（50ms 周期会让日志刷爆）
                if (watchFailing.compareAndSet(false, true)) {
                    log.warn("RoundRobin[{}] 执行锁存活检测失败（持续失败不再逐条打印，恢复时提示）："
                            + "慢 Redis 会让检测与 max-hold 都延迟，届时丢锁发现变晚", task.getName(), e);
                }
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 检测间隔显著超出周期时告警一次：这种延迟没有异常可捕获（池被慢 Redis 占满时只是"跑晚了"），
     * 由本方法量出来并告警
     */
    void reportWatchLag(RoundRobinTask task, long gapNanos) {
        if (gapNanos < 0) {
            return;
        }
        if (gapNanos >= WATCH_LAG_WARN_NANOS) {
            if (watchLagging.compareAndSet(false, true)) {
                log.warn("RoundRobin[{}] 执行锁存活检测延迟 {}ms（周期 {}ms）：调度线程池繁忙（慢 Redis 或任务数过多会占满池线程），"
                                + "丢锁发现与 max-hold 取消都会变晚；业务线程不受影响（独立虚拟线程）、锁续期由 Redisson 内部完成；"
                                + "需要更大并发可用同名 roundRobinScheduler Bean 顶替内置池",
                        task.getName(), Duration.ofNanos(gapNanos).toMillis(), LOCK_CHECK_INTERVAL.toMillis());
            }
            return;
        }
        // 间隔回归正常：下一次拥堵可以再报一条
        watchLagging.set(false);
    }

    /** 检测恢复正常时补一条（持续失败期间只报了首条） */
    private void reportWatchHealthy(RoundRobinTask task) {
        if (watchFailing.compareAndSet(true, false)) {
            log.warn("RoundRobin[{}] 执行锁存活检测已恢复（此前失败只报了首条）", task.getName());
        }
    }

    /**
     * 解锁时才发现本线程字段已不在锁里：本轮执行期间真实丢锁。
     * <p>
     * 任务耗时短于 50ms 检测周期时检测根本不触发，这里是本轮唯一的丢锁信号（解锁结果自身无竞态）；
     * 检测已在任务运行期报过则不重复报
     */
    private static void reportLostOnUnlock(RoundRobinTask task, AtomicBoolean lockLost,
                                           RoundRobinLockClient.UnlockOutcome outcome) {
        if (outcome != RoundRobinLockClient.UnlockOutcome.NOT_HELD || lockLost.get()) {
            return;
        }
        lockLost.set(true);
        Duration hardLease = task.getExecLockTtl();
        if (hardLease == null) {
            log.error("RoundRobin[{}] 释放执行锁时发现锁已易主/被删（看门狗续期模式异常事件：锁被删除或续期失败）："
                    + "本轮任务执行期间可能已有其它实例并发执行，请核查", task.getName());
            return;
        }
        log.error("RoundRobin[{}] 释放执行锁时发现锁已易主/被删：任务时长可能已超过 exec-lock-ttl={}，"
                        + "另一实例可能已并发执行本轮任务（本轮 50ms 检测未触发）；"
                        + "请缩短任务或调大 exec-lock-ttl",
                task.getName(), hardLease);
    }

    /**
     * 丢锁是严重事件（其它实例可能已在并发执行同一任务），分级为 ERROR：
     * 硬租约模式下最可能是任务时长超过租约，日志直接给出排查方向
     */
    private static void logLockLost(RoundRobinTask task) {
        Duration hardLease = task.getExecLockTtl();
        if (hardLease == null) {
            log.error("RoundRobin[{}] 执行锁已丢失（看门狗续期模式异常事件：锁被删除或续期失败），已取消任务上下文；"
                    + "本实例任务可能仍在运行，请核查是否有其它实例并发执行", task.getName());
            return;
        }
        log.error("RoundRobin[{}] 执行锁已丢失：任务时长可能已超过 exec-lock-ttl={}，"
                        + "另一实例可能正在并发执行同一任务（已取消任务上下文）；"
                        + "请缩短任务或调大 exec-lock-ttl",
                task.getName(), hardLease);
    }

    /** 取消 ScheduledFuture 且不中断运行中任务 */
    private static void cancelQuietly(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    static boolean shouldStopOnTickError(CancellationToken scheduleToken) {
        return scheduleToken.isCancelled();
    }

}
