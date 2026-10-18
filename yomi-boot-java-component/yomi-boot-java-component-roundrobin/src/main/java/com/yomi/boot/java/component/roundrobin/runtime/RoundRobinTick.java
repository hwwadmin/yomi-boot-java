package com.yomi.boot.java.component.roundrobin.runtime;

import com.yomi.boot.java.component.roundrobin.RoundRobinContext;
import com.yomi.boot.java.component.roundrobin.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * 单轮取锁与任务执行
 * <p>
 * 持锁期间由 {@link RoundRobinWatch}（独立虚拟线程）每 {@link RoundRobinWatch#INTERVAL} 观测一次：
 * 执行锁存活探测与 max-hold 到点取消。探测线程与业务线程分离，业务线程阻塞在 handler 里也能被观测。
 *
 * @author zwei
 */
@Slf4j
public class RoundRobinTick {

    /** 存活检测延迟告警阈值（10 个观测周期）：超过说明探测被慢 Redis 或虚拟线程调度延迟拖住 */
    private static final long WATCH_LAG_WARN_NANOS = RoundRobinWatch.INTERVAL.toNanos() * 10;

    /** 频率锁与执行锁 */
    private final RoundRobinLockClient lockClient;

    /** 观测器创建口：默认虚拟线程；测试注入假实现以手动驱动探测 */
    private final RoundRobinWatchFactory watchFactory;

    /** 存活检测连续失败中（跨轮次：失败只报首条，恢复时补一条） */
    private final AtomicBoolean watchFailing = new AtomicBoolean();

    /** 已就"检测延迟"告警过（跨轮次：同一次拥堵只报一条，间隔回归正常后复位） */
    private final AtomicBoolean watchLagging = new AtomicBoolean();

    public RoundRobinTick(RoundRobinLockClient lockClient) {
        this(lockClient, RoundRobinWatch::start);
    }

    /** 观测器创建口（测试用） */
    RoundRobinTick(RoundRobinLockClient lockClient, RoundRobinWatchFactory watchFactory) {
        this.lockClient = lockClient;
        this.watchFactory = watchFactory;
    }

    /**
     * @return 是否成功持执行锁并运行了任务
     */
    public boolean tick(RoundRobinContext scheduleContext, RoundRobinTask task) throws Exception {
        if (!lockClient.tryAcquireFrequency(task)) {
            RoundRobinSleep.sleep(scheduleContext, task.getWakeUpInterval());
            return false;
        }

        RLock execLock = lockClient.tryAcquireExecution(task);
        if (execLock == null) {
            RoundRobinSleep.sleep(scheduleContext, task.getWakeUpInterval());
            return false;
        }

        Round round = new Round(task, execLock, Thread.currentThread().threadId(), scheduleContext);
        RoundRobinWatch watch = null;
        try {
            watch = watchFactory.start(task.getName(), round::observe);
            // 任务异常与 Error 都向上抛出：由 RoundRobinLoop 统一统计连续失败并退避（实例线程不退出）
            task.getHandler().run(round.context());
            return true;
        } finally {
            round.finish();
            if (watch != null) {
                watch.stop();
            }
            round.reportLostOnUnlock(lockClient.unlockQuietly(task, execLock));
        }
    }

    /**
     * 检测间隔显著超出周期时告警一次：这种延迟没有异常可捕获（探测被慢 Redis 阻塞或虚拟线程调度延迟，
     * 只是"跑晚了"），由实测间隔量出来并告警
     */
    void reportWatchLag(RoundRobinTask task, long gapNanos) {
        if (gapNanos < 0) {
            return;
        }
        if (gapNanos >= WATCH_LAG_WARN_NANOS) {
            if (watchLagging.compareAndSet(false, true)) {
                log.warn("RoundRobin[{}] 执行锁存活检测延迟 {}ms（周期 {}ms）：探测被慢 Redis 阻塞或虚拟线程调度延迟拖后，"
                                + "丢锁发现与 max-hold 取消都会变晚；业务线程不受影响（独立虚拟线程）、锁续期由 Redisson 内部完成",
                        task.getName(), Duration.ofNanos(gapNanos).toMillis(), RoundRobinWatch.INTERVAL.toMillis());
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

    /**
     * 单轮执行状态：执行锁、观测顺序、max-hold 截止与丢锁上报
     * <p>观测顺序要求：先探活、后读任务状态——任务返回后才出现的字段缺失是本轮 unlock 自己删的，
     * 先读状态的话，那一跳（探测的 Redis 往返落在 unlock 之后）仍会误报丢锁
     */
    private final class Round {

        private final RoundRobinTask task;

        /** 本轮持有的执行锁 */
        private final RLock execLock;

        /** 业务线程 id（锁归属只在业务线程内有意义） */
        private final long workerThreadId;

        /** 单轮上下文：合并调度根与本轮取消（max-hold / 丢锁） */
        private final DefaultRoundRobinContext context;

        /** 丢锁是否已上报（同一次丢锁只报一条） */
        private final AtomicBoolean lockLost = new AtomicBoolean();

        /** 任务是否仍在运行：任务返回后的字段缺失必然是本轮自己的 unlock（观测与 unlock 存在天然竞态） */
        private final AtomicBoolean taskRunning = new AtomicBoolean(true);

        private final AtomicBoolean maxHoldFired = new AtomicBoolean();

        /** max-hold 截止时刻（纳秒）；0 = 未配置 */
        private final long maxHoldDeadlineNanos;

        Round(RoundRobinTask task, RLock execLock, long workerThreadId, RoundRobinContext scheduleContext) {
            this.task = task;
            this.execLock = execLock;
            this.workerThreadId = workerThreadId;
            this.context = new DefaultRoundRobinContext(scheduleContext);
            Duration maxHold = task.getMaxHoldDuration();
            this.maxHoldDeadlineNanos = maxHold == null ? 0L : System.nanoTime() + maxHold.toNanos();
        }

        /** 交给 handler 的单轮上下文 */
        RoundRobinContext context() {
            return context;
        }

        /**
         * 一次观测（观测线程每 {@link RoundRobinWatch#INTERVAL} 回调）：max-hold 到点取消 + 执行锁存活探测。
         * <p>max-hold 在到期后的第一次观测触发；该次探测被慢 Redis 拖住时会顺延到探测结束（协作式软上限，语义不变）
         */
        void observe(long gapNanos) {
            try {
                if (maxHoldDeadlineNanos != 0L && System.nanoTime() >= maxHoldDeadlineNanos
                        && maxHoldFired.compareAndSet(false, true)) {
                    context.cancel();
                    log.warn("RoundRobin[{}] 已达 max-hold-duration，已置取消标志（协作式：handler 不检查 isCancelled 则任务会继续运行，"
                            + "且执行锁仍被续期；请让 handler 轮询 ctx.isCancelled()）", task.getName());
                }
                boolean held = lockClient.isHeldByWorkerThread(execLock, workerThreadId);
                if (!held && taskRunning.get() && lockLost.compareAndSet(false, true)) {
                    logLockLost(task);
                    context.cancel();
                }
                reportWatchHealthy(task);
                reportWatchLag(task, gapNanos);
            } catch (Throwable t) {
                // 观测内兜底（含 Error）：持续故障只报首条、恢复时补一条（观测周期 50ms，逐条报会把日志刷爆）
                if (watchFailing.compareAndSet(false, true)) {
                    log.warn("RoundRobin[{}] 执行锁存活检测失败（持续失败不再逐条打印，恢复时提示）："
                            + "慢 Redis 会让检测与 max-hold 都延迟，届时丢锁发现变晚", task.getName(), t);
                }
            }
        }

        /** 本轮结束：必须先于观测器停止与解锁调用（在途探测据此识别"字段是本轮自己删的"） */
        void finish() {
            taskRunning.set(false);
        }

        /**
         * 解锁时才发现本线程字段已不在锁里：本轮执行期间真实丢锁。
         * <p>任务耗时短于观测周期时观测根本不触发，这里是本轮唯一的丢锁信号（解锁结果自身无竞态）；
         * 观测已在任务运行期报过则不重复报
         */
        void reportLostOnUnlock(RoundRobinLockClient.UnlockOutcome outcome) {
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
                            + "另一实例可能已并发执行本轮任务（本轮 {}ms 观测未触发）；"
                            + "请缩短任务或调大 exec-lock-ttl",
                    task.getName(), hardLease, RoundRobinWatch.INTERVAL.toMillis());
        }

    }

    /** 观测器创建口：默认 {@link RoundRobinWatch#start}；测试注入假实现以手动驱动探测 */
    @FunctionalInterface
    interface RoundRobinWatchFactory {

        RoundRobinWatch start(String taskName, LongConsumer onTick);

    }

}
