package com.yomi.boot.java.component.roundrobin.runtime;

import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单轮抢锁与任务执行
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class RoundRobinTick {

    /** 分布式锁续期间隔 */
    private static final Duration LOCK_CHECK_INTERVAL = Duration.ofMillis(50);

    /** 频率锁与执行锁 */
    private final RoundRobinLockClient lockClient;

    /** max-hold 与丢锁检测 */
    private final ScheduledExecutorService scheduler;

    /**
     * @return 是否成功持执行锁并运行了任务
     */
    public boolean tick(CancellationToken scheduleToken, RoundRobinConfig config, RoundRobinHandler task)
            throws Exception {
        if (!lockClient.tryAcquireFrequency(config)) {
            RoundRobinSleep.sleep(scheduleToken, config.getWakeUpInterval());
            return false;
        }

        RLock execLock = lockClient.tryAcquireExecution(config);
        if (execLock == null) {
            RoundRobinSleep.sleep(scheduleToken, config.getWakeUpInterval());
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
                maxHoldFuture = scheduleMaxHold(config, runCtx);
                lockWatchFuture = scheduleLockWatch(config, execLock, workerThreadId, runCtx, lockLost, taskRunning);
            } catch (Exception e) {
                // 调度线程池不可用（如宿主提供的池被关停）：无法建立丢锁检测就不跑任务，
                // 释放执行锁等下一轮；绝不能把异常抛到 try 之外，否则 execLock 泄漏，
                // 看门狗模式下会被永久续期、其它实例再也接管不了
                log.warn("RoundRobin[{}] 无法在调度线程池上建立 maxHold/丢锁检测，本轮跳过执行（执行锁已释放）",
                        config.getName(), e);
                scheduleUnavailable = true;
            }
            if (!scheduleUnavailable) {
                // 任务异常向上抛出，由 RoundRobinLoop 统计连续失败并退避
                runTaskSafely(runCtx, config.getName(), task);
                return true;
            }
        } finally {
            // 必须先于 cancel/unlock：在途的那次检测若落在 unlock 之后，靠它识别"字段是本轮自己删的"
            taskRunning.set(false);
            cancelQuietly(maxHoldFuture);
            cancelQuietly(lockWatchFuture);
            reportLostOnUnlock(config, lockLost, lockClient.unlockQuietly(config, execLock));
        }
        RoundRobinSleep.sleep(scheduleToken, config.getWakeUpInterval());
        return false;
    }

    /** 注册 max-hold 超时取消任务 */
    private ScheduledFuture<?> scheduleMaxHold(RoundRobinConfig config, DefaultRoundRobinContext runCtx) {
        Duration maxHold = config.getMaxHoldDuration();
        if (maxHold == null) {
            return null;
        }
        return scheduler.schedule(() -> {
            runCtx.cancel();
            log.warn("RoundRobin[{}] 已达 max-hold-duration，已置取消标志（协作式：handler 不检查 isCancelled 则任务会继续运行，"
                    + "且执行锁仍被续期；请让 handler 轮询 ctx.isCancelled()，或改用 exec-watchdog=false 的硬租约）",
                    config.getName());
        }, maxHold.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 注册执行锁存活检测任务 */
    private ScheduledFuture<?> scheduleLockWatch(RoundRobinConfig config, RLock lock, long workerThreadId,
                                                 DefaultRoundRobinContext runCtx, AtomicBoolean lockLost,
                                                 AtomicBoolean taskRunning) {
        long periodMs = LOCK_CHECK_INTERVAL.toMillis();
        return scheduler.scheduleAtFixedRate(() -> {
            try {
                if (lockClient.isHeldByWorkerThread(lock, workerThreadId)) {
                    return;
                }
                // 顺序不能反：必须先探活、后读任务状态。任务返回后才出现的字段缺失是本轮 unlock 自己删的，
                // 若先读状态，那一跳（检测的 Redis 往返落在 unlock 之后）仍会误报丢锁
                if (!taskRunning.get()) {
                    return;
                }
                if (lockLost.compareAndSet(false, true)) {
                    logLockLost(config);
                    runCtx.cancel();
                }
            } catch (Exception e) {
                // 定时任务内捕获异常并打日志，避免后续周期被取消
                log.warn("RoundRobin[{}] 执行锁存活检测失败，下个周期继续检测", config.getName(), e);
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 解锁时才发现本线程字段已不在锁里：本轮执行期间真实丢锁。
     * <p>
     * 任务耗时短于 50ms 检测周期时检测根本不触发，这里是本轮唯一的丢锁信号（解锁结果自身无竞态）；
     * 检测已在任务运行期报过则不重复报
     */
    private static void reportLostOnUnlock(RoundRobinConfig config, AtomicBoolean lockLost,
                                           RoundRobinLockClient.UnlockOutcome outcome) {
        if (outcome != RoundRobinLockClient.UnlockOutcome.NOT_HELD || lockLost.get()) {
            return;
        }
        lockLost.set(true);
        if (config.isExecWatchdog()) {
            log.error("RoundRobin[{}] 释放执行锁时发现锁已易主/被删（看门狗模式异常事件：锁被删除或续期失败）："
                    + "本轮任务执行期间可能已有其它实例并发执行，请核查", config.getName());
            return;
        }
        log.error("RoundRobin[{}] 释放执行锁时发现锁已易主/被删：任务时长可能已超过 exec-lock-ttl={}，"
                        + "另一实例可能已并发执行本轮任务（本轮 50ms 检测未触发）；"
                        + "请缩短任务、调大 exec-lock-ttl，或开启 exec-watchdog 改为续期模式",
                config.getName(), config.getExecLockTtl());
    }

    /**
     * 丢锁是严重事件（其它实例可能已在并发执行同一任务），分级为 ERROR：
     * watchdog=false 时最可能是任务时长超过硬租约，日志直接给出排查方向
     */
    private static void logLockLost(RoundRobinConfig config) {
        if (config.isExecWatchdog()) {
            log.error("RoundRobin[{}] 执行锁已丢失（看门狗模式异常事件：锁被删除或续期失败），已取消任务上下文；"
                    + "本实例任务可能仍在运行，请核查是否有其它实例并发执行", config.getName());
            return;
        }
        log.error("RoundRobin[{}] 执行锁已丢失：任务时长可能已超过 exec-lock-ttl={}，"
                        + "另一实例可能正在并发执行同一任务（已取消任务上下文）；"
                        + "请缩短任务、调大 exec-lock-ttl，或开启 exec-watchdog 改为续期模式",
                config.getName(), config.getExecLockTtl());
    }

    /**
     * 执行任务：{@link Exception} 向上抛，由 {@link RoundRobinLoop} 记录并按连续失败退避；
     * {@link Error} 记录 panic 日志后不向外抛，避免 worker 线程退出
     */
    private void runTaskSafely(RoundRobinContext ctx, String name, RoundRobinHandler task) throws Exception {
        try {
            task.run(ctx);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            log.error("RoundRobin[{}] 轮询任务 panic", name, t);
        }
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
