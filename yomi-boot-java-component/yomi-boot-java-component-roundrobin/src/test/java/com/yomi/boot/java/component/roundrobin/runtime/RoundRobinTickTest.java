package com.yomi.boot.java.component.roundrobin.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.roundrobin.RoundRobin;
import com.yomi.boot.java.component.roundrobin.RoundRobinHandler;
import com.yomi.boot.java.component.roundrobin.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient.UnlockOutcome;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoundRobinTickTest {

    /**
     * 丢锁是严重事件（硬租约下最可能是任务超租约、其它实例并发执行）：
     * 记 ERROR 并给出排查方向，这是这类问题唯一的可观测信号
     */
    @Test
    void lockLossIsLoggedAsErrorWithLeaseHintForHardLease() {
        String logs = driveLostLock(true);

        assertTrue(logs.contains("exec-lock-ttl=PT30S"), "报错应带上实际租约值: " + logs);
        assertTrue(logs.contains("并发执行"), "报错应点明并发执行后果: " + logs);
    }

    @Test
    void lockLossUnderWatchdogIsLoggedAsAbnormalEvent() {
        String logs = driveLostLock(false);

        assertTrue(logs.contains("看门狗"), "续期模式丢锁属异常事件，文案应区分: " + logs);
    }

    /** 任务异常向上抛出，执行锁在 finally 中释放 */
    @Test
    void taskExceptionPropagatesAndLockIsReleased() {
        HeldLockClient lockClient = new HeldLockClient();
        RoundRobinTick tick = new RoundRobinTick(lockClient);

        assertThrows(IllegalStateException.class, () -> tick.tick(DefaultRoundRobinContext.root(), task(ctx -> {
            throw new IllegalStateException("下游故障");
        })));

        assertEquals(1, lockClient.unlocked.size(), "任务失败也必须释放执行锁");
    }

    /**
     * 任务 Error 向上抛（{@link RoundRobinLoop} 计入连续失败并保持 worker 存活），执行锁必须释放。
     * <p>连续失败因此不会被清零，任务按退避节奏重试</p>
     */
    @Test
    void taskErrorPropagatesAndLockIsReleased() {
        HeldLockClient lockClient = new HeldLockClient();
        RoundRobinTick tick = new RoundRobinTick(lockClient);

        NoClassDefFoundError panic = assertThrows(NoClassDefFoundError.class,
                () -> tick.tick(DefaultRoundRobinContext.root(), task(ctx -> {
                    throw new NoClassDefFoundError("boom");
                })));

        assertEquals("boom", panic.getMessage());
        assertEquals(1, lockClient.unlocked.size(), "任务 panic 也必须释放执行锁");
    }

    /** max-hold 到点：置取消标志 + WARN，只触发一次（协作式，不中断任务） */
    @Test
    void maxHoldFiresOnceAtDeadlineAndCancelsContext() {
        AtomicReference<LongConsumer> probe = new AtomicReference<>();
        RoundRobinTick tick = tickWithProbeCapture(new HeldLockClient(), probe);
        AtomicBoolean cancelledBeforeDeadline = new AtomicBoolean(true);
        AtomicBoolean cancelledAfterDeadline = new AtomicBoolean();

        RoundRobinTask task = RoundRobin.task("lost-lock-task", Duration.ofSeconds(1))
                .maxHoldDuration(Duration.ofMillis(50))
                .handler(ctx -> {
                    probe.get().accept(-1);                     // 未到点：不得取消
                    cancelledBeforeDeadline.set(ctx.isCancelled());
                    Thread.sleep(60);                           // 越过 max-hold 截止时刻
                    probe.get().accept(-1);                     // 到点：取消 + WARN
                    probe.get().accept(-1);                     // 再观测：不得重复告警
                    cancelledAfterDeadline.set(ctx.isCancelled());
                })
                .build();

        String logs = tickLogs(tick, task);

        assertFalse(cancelledBeforeDeadline.get(), "未到 max-hold 截止时刻不得取消: " + logs);
        assertTrue(cancelledAfterDeadline.get(), "到点后必须置取消标志: " + logs);
        assertEquals(1, logs.lines().filter(line -> line.contains("max-hold-duration")).count(),
                "max-hold 只触发一次: " + logs);
    }

    /**
     * 观测持续失败只报首条（50ms 周期逐条报会把日志刷爆），恢复时补一条
     */
    @Test
    void watchFailureIsReportedOnceUntilRecovered() {
        AtomicBoolean failProbe = new AtomicBoolean(true);
        HeldLockClient lockClient = new HeldLockClient() {
            @Override
            public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
                if (failProbe.get()) {
                    throw new IllegalStateException("redis 抖动");
                }
                return true;
            }
        };

        AtomicReference<LongConsumer> probe = new AtomicReference<>();
        RoundRobinTick tick = tickWithProbeCapture(lockClient, probe);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinTick.class);
        logger.addAppender(appender);
        try {
            // 跑一轮拿到观测回调（本轮不需要探测）
            assertDoesNotThrow(() -> tick.tick(DefaultRoundRobinContext.root(), task(ctx -> {
            })));
            probe.get().accept(-1);
            probe.get().accept(-1);
            probe.get().accept(-1);
            failProbe.set(false);
            probe.get().accept(-1);
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage())
                .collect(Collectors.joining("\n"));
        assertEquals(1, logs.lines().filter(line -> line.contains("执行锁存活检测失败")).count(),
                "连续失败只报首条: " + logs);
        assertTrue(logs.contains("执行锁存活检测已恢复"), "恢复时必须补一条: " + logs);
    }

    /**
     * 检测延迟没有异常可捕获（探测被慢 Redis 阻塞时只是"跑晚了"），由实测间隔量出：
     * 同一次拥堵只报一条，间隔回归正常后可再报
     */
    @Test
    void watchLagIsReportedOncePerIncident() {
        RoundRobinTick tick = new RoundRobinTick(new HeldLockClient());
        RoundRobinTask task = task(ctx -> {
        });

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinTick.class);
        logger.addAppender(appender);
        try {
            tick.reportWatchLag(task, -1);                                  // 本轮首次：不算间隔
            tick.reportWatchLag(task, Duration.ofSeconds(1).toNanos());     // 拥堵：报一条
            tick.reportWatchLag(task, Duration.ofSeconds(2).toNanos());     // 同一次拥堵：不重复
            tick.reportWatchLag(task, Duration.ofMillis(50).toNanos());     // 回归正常：复位
            tick.reportWatchLag(task, Duration.ofSeconds(1).toNanos());     // 再次拥堵：可再报
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertEquals(2, logs.lines().filter(line -> line.contains("执行锁存活检测延迟")).count(),
                "每次拥堵只报一条、恢复后可再报: " + logs);
        assertTrue(logs.contains("慢 Redis"), "告警应给出排查方向: " + logs);
    }

    /** 真实计时：探测被慢 Redis 拖长时，实测间隔必须量出并告警 */
    @Test
    void watchLagIsReportedWhenProbeIsSlowedByRedis() {
        AtomicBoolean slowOnce = new AtomicBoolean(true);
        HeldLockClient lockClient = new HeldLockClient() {
            @Override
            public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
                if (slowOnce.compareAndSet(true, false)) {
                    try {
                        Thread.sleep(600);      // 模拟一次慢 Redis：首次探测占用 600ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return true;
            }
        };
        RoundRobinTick tick = new RoundRobinTick(lockClient);

        // 轮次远长于"慢探测 + 一个观测周期"，保证告警在轮内打出
        String logs = tickLogs(tick, task(ctx -> Thread.sleep(1500)));

        assertTrue(logs.contains("执行锁存活检测延迟"), "按真实间隔量出的延迟必须告警: " + logs);
    }

    /**
     * 任务已结束后，在途的那次观测才发现字段缺失（观测与 unlock 的天然竞态）：
     * 这是本轮自己的 unlock，不报丢锁
     */
    @Test
    void watchLandingAfterTaskFinishedIsNotReportedAsLost() {
        AtomicReference<LongConsumer> probe = new AtomicReference<>();
        HeldLockClient lockClient = new HeldLockClient() {
            @Override
            public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
                return false;   // 字段已被本轮 unlock 删掉
            }

            @Override
            public UnlockOutcome unlockQuietly(RoundRobinTask task, RLock lock) {
                UnlockOutcome outcome = super.unlockQuietly(task, lock);
                probe.get().accept(-1);     // 模拟"已取出、正做 Redis 往返"的那次观测落在 unlock 之后
                return outcome;
            }
        };

        String logs = tickLogs(tickWithProbeCapture(lockClient, probe), task(ctx -> {
        }));

        assertFalse(logs.contains("执行锁已丢失"), "任务结束后的字段缺失是本轮自己解锁，不能报丢锁: " + logs);
    }

    /** 解锁时才发现字段已不在锁里：任务已结束也必须报——任务短于观测周期时这是本轮唯一的丢锁信号 */
    @Test
    void unlockFindingLockTakenOverIsReportedAsLost() {
        HeldLockClient lockClient = new HeldLockClient() {
            @Override
            public UnlockOutcome unlockQuietly(RoundRobinTask task, RLock lock) {
                return UnlockOutcome.NOT_HELD;
            }
        };

        String logs = tickLogs(new RoundRobinTick(lockClient), hardLeaseTask(ctx -> {
        }));

        assertTrue(logs.contains("释放执行锁时发现锁已易主"), "解锁发现易主属真丢锁，必须记 ERROR: " + logs);
        assertTrue(logs.contains("exec-lock-ttl=PT30S"), "硬租约模式应带租约值与排查方向: " + logs);
    }

    /** 观测已在任务运行期报过丢锁，解锁再发现易主只报一条 */
    @Test
    void unlockFindingTakenOverAfterWatchReportedIsNotDuplicated() {
        AtomicReference<LongConsumer> probe = new AtomicReference<>();
        LostLockClient lockClient = new LostLockClient() {
            @Override
            public UnlockOutcome unlockQuietly(RoundRobinTask task, RLock lock) {
                return UnlockOutcome.NOT_HELD;
            }
        };

        String logs = tickLogs(tickWithProbeCapture(lockClient, probe), task(ctx -> probe.get().accept(-1)));

        assertEquals(1, logs.lines().filter(line -> line.contains("ERROR")).count(), "同一次丢锁只能报一条: " + logs);
    }

    /** 驱动一次 tick：锁客户端桩报告"锁已丢失"，任务体内手动触发一次观测 */
    private String driveLostLock(boolean hardLease) {
        AtomicReference<LongConsumer> probe = new AtomicReference<>();
        RoundRobinTick tick = tickWithProbeCapture(new LostLockClient(), probe);
        RoundRobinHandler probeThenRun = ctx -> probe.get().accept(-1);
        String logs = tickLogs(tick, hardLease ? hardLeaseTask(probeThenRun) : task(probeThenRun));
        assertTrue(logs.contains(Level.ERROR.toString()), "丢锁必须记 ERROR: " + logs);
        return logs;
    }

    /** 注入假观测器：抓住观测回调，由用例手动驱动（不依赖真实计时） */
    private static RoundRobinTick tickWithProbeCapture(RoundRobinLockClient lockClient,
                                                       AtomicReference<LongConsumer> probe) {
        return new RoundRobinTick(lockClient, (taskName, onTick) -> {
            probe.set(onTick);
            return () -> {
            };
        });
    }

    /** 跑一次 tick，返回本轮 RoundRobinTick 的日志（级别 + 文案） */
    private String tickLogs(RoundRobinTick tick, RoundRobinTask task) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinTick.class);
        logger.addAppender(appender);
        try {
            tick.tick(DefaultRoundRobinContext.root(), task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new AssertionError("本场景不应抛异常", e);
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                .collect(Collectors.joining("\n"));
    }

    /** 看门狗续期任务（默认），丢锁文案为"异常事件"口径 */
    private static RoundRobinTask task(RoundRobinHandler handler) {
        return RoundRobin.task("lost-lock-task", Duration.ofSeconds(1), handler);
    }

    /** 硬租约任务：丢锁文案带租约值与排查方向 */
    private static RoundRobinTask hardLeaseTask(RoundRobinHandler handler) {
        return RoundRobin.task("lost-lock-task", Duration.ofSeconds(1))
                .execLockTtl(Duration.ofSeconds(30))
                .handler(handler)
                .build();
    }

    /** 构造 RLock 动态代理桩 */
    private static RLock proxyLock() {
        return (RLock) Proxy.newProxyInstance(RoundRobinTickTest.class.getClassLoader(),
                new Class<?>[]{RLock.class}, (proxy, method, args) -> null);
    }

    /** 锁客户端桩：频率锁/执行锁都能拿到，但存活探测永远报告"已丢失" */
    static class LostLockClient extends RoundRobinLockClient {

        LostLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinTask task) {
            return true;
        }

        @Override
        public RLock tryAcquireExecution(RoundRobinTask task) {
            return proxyLock();
        }

        @Override
        public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
            return false;
        }

        @Override
        public UnlockOutcome unlockQuietly(RoundRobinTask task, RLock lock) {
            // 测试桩：不触达 Redis
            return UnlockOutcome.RELEASED;
        }
    }

    /** 锁客户端桩：锁被视为一直持有，并记录释放次数（验证 finally 语义） */
    static class HeldLockClient extends RoundRobinLockClient {

        final List<String> unlocked = new ArrayList<>();

        HeldLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinTask task) {
            return true;
        }

        @Override
        public RLock tryAcquireExecution(RoundRobinTask task) {
            return proxyLock();
        }

        @Override
        public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
            return true;
        }

        @Override
        public UnlockOutcome unlockQuietly(RoundRobinTask task, RLock lock) {
            unlocked.add(task.getName());
            return UnlockOutcome.RELEASED;
        }
    }

}
