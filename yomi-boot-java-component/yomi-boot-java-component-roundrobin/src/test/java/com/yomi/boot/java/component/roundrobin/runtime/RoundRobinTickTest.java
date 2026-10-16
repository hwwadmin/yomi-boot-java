package com.yomi.boot.java.component.roundrobin.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient.UnlockOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoundRobinTickTest {

    private ScheduledExecutorService scheduler;

    @AfterEach
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldStopOnlyWhenScheduleCancelled() {
        CancellationToken alive = new CancellationToken();
        assertFalse(RoundRobinTick.shouldStopOnTickError(alive));

        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();
        assertTrue(RoundRobinTick.shouldStopOnTickError(cancelled));
    }

    /**
     * 丢锁是严重事件（watchdog=false 时最可能是任务超租约、其它实例并发执行），
     * 必须是 ERROR 且文案给出排查方向——它也是这类问题唯一的可观测信号
     */
    @Test
    void lockLossIsLoggedAsErrorWithLeaseHintWhenWatchdogOff() {
        String logs = driveLostLock(false);

        assertTrue(logs.contains("exec-lock-ttl=PT30S"), "报错应带上实际租约值: " + logs);
        assertTrue(logs.contains("并发执行"), "报错应点明并发执行后果: " + logs);
    }

    @Test
    void lockLossUnderWatchdogIsLoggedAsAbnormalEvent() {
        String logs = driveLostLock(true);

        assertTrue(logs.contains("看门狗"), "看门狗模式丢锁属异常事件，文案应区分: " + logs);
    }

    /** 任务异常向上抛出，执行锁在 finally 中释放 */
    @Test
    void taskExceptionPropagatesAndLockIsReleased() {
        scheduler = Executors.newScheduledThreadPool(1);
        HeldLockClient lockClient = new HeldLockClient();
        RoundRobinTick tick = new RoundRobinTick(lockClient, scheduler);

        assertThrows(IllegalStateException.class, () -> tick.tick(new CancellationToken(), config(true),
                ctx -> {
                    throw new IllegalStateException("下游故障");
                }));

        assertEquals(1, lockClient.unlocked.size(), "任务失败也必须释放执行锁");
    }

    /** 调度线程池不可用时跳过本轮执行并释放执行锁 */
    @Test
    void schedulingFailureSkipsTaskAndReleasesLock() {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.shutdown();
        HeldLockClient lockClient = new HeldLockClient();
        RoundRobinTick tick = new RoundRobinTick(lockClient, scheduler);
        AtomicInteger runs = new AtomicInteger();

        boolean executed = assertDoesNotThrow(
                () -> tick.tick(new CancellationToken(), config(true), ctx -> runs.incrementAndGet()));

        assertFalse(executed, "无法建立丢锁检测时不得执行任务");
        assertEquals(0, runs.get(), "任务不得裸跑");
        assertEquals(1, lockClient.unlocked.size(), "执行锁必须释放（不能抛到 try 之外导致泄漏）");
    }

    /**
     * 任务 Error 向上抛（worker 存活由 {@link RoundRobinLoop} 兜住并计入连续失败），执行锁必须释放。
     * <p>旧行为是"吞掉记成功"：tick 返回 true，Loop 把连续失败清零、按正常频率无限重试</p>
     */
    @Test
    void taskErrorPropagatesAndLockIsReleased() {
        scheduler = Executors.newScheduledThreadPool(1);
        HeldLockClient lockClient = new HeldLockClient();
        RoundRobinTick tick = new RoundRobinTick(lockClient, scheduler);

        NoClassDefFoundError panic = assertThrows(NoClassDefFoundError.class,
                () -> tick.tick(new CancellationToken(), config(true), ctx -> {
                    throw new NoClassDefFoundError("boom");
                }));

        assertEquals("boom", panic.getMessage());
        assertEquals(1, lockClient.unlocked.size(), "任务 panic 也必须释放执行锁");
    }

    /**
     * 存活检测持续失败只报首条（50ms 周期逐条报会把日志刷爆），恢复时补一条
     */
    @Test
    void watchFailureIsReportedOnceUntilRecovered() {
        AtomicReference<Runnable> watch = new AtomicReference<>();
        scheduler = trapWatchScheduler(watch);
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

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinTick.class);
        logger.addAppender(appender);
        try {
            // 让检测任务建起来（tick 内注册进 trap 调度器）
            assertDoesNotThrow(() -> tickQuietly(lockClient, ctx -> {
            }));
            watch.get().run();
            watch.get().run();
            watch.get().run();
            failProbe.set(false);
            watch.get().run();
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
     * 检测延迟必须被量出来（池被慢 Redis 占满时只是"跑晚了"，没有异常可捕获）：
     * 同一次拥堵只报一条，间隔回归正常后可再报
     */
    @Test
    void watchLagIsReportedOncePerIncident() {
        scheduler = Executors.newScheduledThreadPool(1);
        RoundRobinTick tick = new RoundRobinTick(new HeldLockClient(), scheduler);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinTick.class);
        logger.addAppender(appender);
        try {
            tick.reportWatchLag(config(true), -1);                                  // 首次执行：不算间隔
            tick.reportWatchLag(config(true), Duration.ofSeconds(1).toNanos());     // 拥堵：报一条
            tick.reportWatchLag(config(true), Duration.ofSeconds(2).toNanos());     // 同一次拥堵：不重复
            tick.reportWatchLag(config(true), Duration.ofMillis(50).toNanos());     // 回归正常：复位
            tick.reportWatchLag(config(true), Duration.ofSeconds(1).toNanos());     // 再次拥堵：可再报
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertEquals(2, logs.lines().filter(line -> line.contains("执行锁存活检测延迟")).count(),
                "每次拥堵只报一条、恢复后可再报: " + logs);
        assertTrue(logs.contains("roundRobinScheduler"), "告警应给出扩容出路: " + logs);
    }

    /** 检测间隔真被拖长时（走真实计时）也必须告警 */
    @Test
    void watchLagIsDetectedFromMeasuredGap() throws Exception {
        AtomicReference<Runnable> watch = new AtomicReference<>();
        scheduler = trapWatchScheduler(watch);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinTick.class);
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> tickQuietly(new HeldLockClient(), ctx -> {
            }));
            watch.get().run();                      // 首跑只记时刻
            Thread.sleep(600);                      // 模拟池繁忙：检测被拖后远超 10 个周期（500ms）
            watch.get().run();
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertTrue(logs.contains("执行锁存活检测延迟"), "按真实间隔量出的延迟必须告警: " + logs);
    }

    /** 跑一次 tick（仅用于把检测任务注册进 trap 调度器），本轮异常交给调用方处理 */
    private void tickQuietly(RoundRobinLockClient lockClient, RoundRobinHandler task) throws Exception {
        new RoundRobinTick(lockClient, scheduler).tick(new CancellationToken(), config(true), task);
    }

    /**
     * 任务已结束后，在途的那次存活检测才发现字段缺失（50ms 检测与 unlock 的天然竞态）：
     * 这是本轮自己的 unlock，不得报丢锁——线上那条误导性"执行锁已丢失"就是这个竞态
     */
    @Test
    void watchLandingAfterTaskFinishedIsNotReportedAsLost() {
        AtomicReference<Runnable> watch = new AtomicReference<>();
        scheduler = trapWatchScheduler(watch);
        HeldLockClient lockClient = new HeldLockClient() {
            @Override
            public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
                return false;   // 字段已被本轮 unlock 删掉
            }

            @Override
            public UnlockOutcome unlockQuietly(RoundRobinConfig config, RLock lock) {
                UnlockOutcome outcome = super.unlockQuietly(config, lock);
                watch.get().run();      // 模拟"已取出、正做 Redis 往返"的那次检测落在 unlock 之后
                return outcome;
            }
        };

        String logs = tickLogs(lockClient, config(true), ctx -> { });

        assertFalse(logs.contains("执行锁已丢失"), "任务结束后的字段缺失是本轮自己解锁，不能报丢锁: " + logs);
    }

    /** 解锁时才发现字段已不在锁里：任务已结束也必须报——任务短于 50ms 检测周期时这是本轮唯一的丢锁信号 */
    @Test
    void unlockFindingLockTakenOverIsReportedAsLost() {
        scheduler = Executors.newScheduledThreadPool(1);
        HeldLockClient lockClient = new HeldLockClient() {
            @Override
            public UnlockOutcome unlockQuietly(RoundRobinConfig config, RLock lock) {
                return UnlockOutcome.NOT_HELD;
            }
        };

        String logs = tickLogs(lockClient, config(false), ctx -> { });

        assertTrue(logs.contains("释放执行锁时发现锁已易主"), "解锁发现易主属真丢锁，必须记 ERROR: " + logs);
        assertTrue(logs.contains("exec-lock-ttl=PT30S"), "硬租约模式应带租约值与排查方向: " + logs);
    }

    /** 检测已在任务运行期报过丢锁，解锁再发现易主不得重复报 */
    @Test
    void unlockFindingTakenOverAfterWatchReportedIsNotDuplicated() {
        scheduler = Executors.newScheduledThreadPool(1);
        LostLockClient lockClient = new LostLockClient() {
            @Override
            public UnlockOutcome unlockQuietly(RoundRobinConfig config, RLock lock) {
                return UnlockOutcome.NOT_HELD;
            }
        };

        String logs = tickLogs(lockClient, config(true), ctx -> Thread.sleep(250));

        assertEquals(1, logs.lines().filter(line -> line.contains("ERROR")).count(), "同一次丢锁只能报一条: " + logs);
    }

    /** 驱动一次 tick：锁客户端桩报告"锁已丢失"，任务运行 250ms 让 50ms 周期的存活检测有机会触发 */
    private String driveLostLock(boolean watchdog) {
        scheduler = Executors.newScheduledThreadPool(1);
        String logs = tickLogs(new LostLockClient(), config(watchdog), ctx -> Thread.sleep(250));
        assertTrue(logs.contains(Level.ERROR.toString()), "丢锁必须记 ERROR: " + logs);
        return logs;
    }

    /** 跑一次 tick，返回本轮 RoundRobinTick 的日志（级别 + 文案） */
    private String tickLogs(RoundRobinLockClient lockClient, RoundRobinConfig config, RoundRobinHandler task) {
        RoundRobinTick tick = new RoundRobinTick(lockClient, scheduler);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinTick.class);
        logger.addAppender(appender);
        try {
            tick.tick(new CancellationToken(), config, task);
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

    /** 调度器桩：扣下 50ms 存活检测任务不回放，交给测试在需要的时机执行 */
    private static ScheduledExecutorService trapWatchScheduler(AtomicReference<Runnable> watch) {
        return (ScheduledExecutorService) Proxy.newProxyInstance(RoundRobinTickTest.class.getClassLoader(),
                new Class<?>[]{ScheduledExecutorService.class}, (proxy, method, args) -> {
                    if ("scheduleAtFixedRate".equals(method.getName())) {
                        watch.set((Runnable) args[0]);
                        return Proxy.newProxyInstance(RoundRobinTickTest.class.getClassLoader(),
                                new Class<?>[]{ScheduledFuture.class}, (p, m, a) -> proxyDefault(m.getReturnType()));
                    }
                    return proxyDefault(method.getReturnType());
                });
    }

    /** 动态代理桩的返回值：primitive 返回 null 会抛 NPE（如 ScheduledFuture.cancel） */
    private static Object proxyDefault(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }

    /** 构造 RoundRobin 测试配置 */
    private static RoundRobinConfig config(boolean watchdog) {
        return RoundRobinConfig.builder()
                .name("lost-lock-task")
                .frequency(Duration.ofSeconds(1))
                .randomDelayMin(Duration.ofMillis(100))
                .randomDelayMax(Duration.ofMillis(200))
                .wakeUpInterval(Duration.ofMillis(100))
                .execLockTtl(Duration.ofSeconds(30))
                .execWatchdog(watchdog)
                .lockWait(Duration.ZERO)
                .shutdownAwait(Duration.ofSeconds(30))
                .build();
    }

    /** 构造 RLock 动态代理桩 */
    private static RLock proxyLock() {
        return (RLock) Proxy.newProxyInstance(RoundRobinTickTest.class.getClassLoader(),
                new Class<?>[]{RLock.class}, (proxy, method, args) -> null);
    }

    /** 锁客户端桩：频率锁/执行锁都能拿到，但存活检测永远报告"已丢失" */
    static class LostLockClient extends RoundRobinLockClient {

        LostLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinConfig config) {
            return true;
        }

        @Override
        public RLock tryAcquireExecution(RoundRobinConfig config) {
            return proxyLock();
        }

        @Override
        public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
            return false;
        }

        @Override
        public UnlockOutcome unlockQuietly(RoundRobinConfig config, RLock lock) {
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
        public boolean tryAcquireFrequency(RoundRobinConfig config) {
            return true;
        }

        @Override
        public RLock tryAcquireExecution(RoundRobinConfig config) {
            return proxyLock();
        }

        @Override
        public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
            return true;
        }

        @Override
        public UnlockOutcome unlockQuietly(RoundRobinConfig config, RLock lock) {
            unlocked.add(config.getName());
            return UnlockOutcome.RELEASED;
        }
    }

}
