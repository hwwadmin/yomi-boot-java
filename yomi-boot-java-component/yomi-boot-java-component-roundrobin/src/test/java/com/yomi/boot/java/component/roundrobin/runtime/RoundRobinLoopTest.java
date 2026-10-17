package com.yomi.boot.java.component.roundrobin.runtime;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务异常触发连续失败计数与指数退避；任务/框架层 Error 一视同仁（都计入连续失败）；
 * 循环自身出错则记 ERROR 退出（日志与正常停机可区分）
 */
class RoundRobinLoopTest {

    private ScheduledExecutorService scheduler;

    @AfterEach
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void taskFailuresAreCountedAndBackedOff() throws Exception {
        scheduler = Executors.newScheduledThreadPool(1);
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();
        RoundRobinLoop loop = new RoundRobinLoop(new RoundRobinTick(new HeldLockClient(), scheduler));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinLoop.class);
        logger.addAppender(appender);
        try {
            Thread worker = Thread.ofVirtual().start(() -> loop.run(token, config(), ctx -> {
                if (runs.incrementAndGet() >= 2) {
                    token.cancel();
                }
                throw new IllegalStateException("下游故障");
            }));
            worker.join(10_000);
        } finally {
            logger.detachAppender(appender);
        }

        String logs = logs(appender);
        assertTrue(runs.get() >= 2, "任务应被重复执行（失败后仍要按退避节奏重试）");
        assertTrue(logs.contains("consecutiveErrors=1"), "第一次失败必须计入连续失败并退避: " + logs);
        assertTrue(logs.contains("退避"), "日志应给出本次退避时长: " + logs);
        assertTrue(logs.contains("下游故障"), "日志应带上原始异常: " + logs);
    }

    /**
     * 任务恒抛 Error（如 NoClassDefFoundError）与 Exception 同等计入连续失败并退避：
     * 按退避节奏重试，连续失败计数与日志持续增长
     */
    @Test
    void taskErrorsAreCountedAndBackedOff() throws Exception {
        scheduler = Executors.newScheduledThreadPool(1);
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();
        RoundRobinLoop loop = new RoundRobinLoop(new RoundRobinTick(new HeldLockClient(), scheduler));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinLoop.class);
        logger.addAppender(appender);
        try {
            Thread worker = Thread.ofVirtual().start(() -> loop.run(token, config(), ctx -> {
                if (runs.incrementAndGet() >= 2) {
                    token.cancel();
                }
                throw new NoClassDefFoundError("boom");
            }));
            worker.join(10_000);
        } finally {
            logger.detachAppender(appender);
        }

        String logs = logs(appender);
        assertTrue(runs.get() >= 2, "worker 不得因任务 Error 退出（否则第 2 轮不会再执行）: " + logs);
        assertTrue(logs.contains("consecutiveErrors=1"), "任务 Error 必须计入连续失败并退避: " + logs);
        assertTrue(logs.contains("NoClassDefFoundError"), "日志必须带上原始 Error: " + logs);
    }

    /**
     * 框架层 Error（示例为锁客户端抛 StackOverflowError，实际如 Redisson 栈内 OOM）：
     * 同样计入连续失败并退避，worker 不退出
     */
    @Test
    void frameworkErrorsAreCountedAndBackedOff() throws Exception {
        scheduler = Executors.newScheduledThreadPool(1);
        CancellationToken token = new CancellationToken();
        AtomicInteger failures = new AtomicInteger();
        RoundRobinLoop loop = new RoundRobinLoop(new RoundRobinTick(
                new HeldLockClient() {
                    @Override
                    public boolean tryAcquireFrequency(RoundRobinConfig config) {
                        if (failures.incrementAndGet() >= 2) {
                            token.cancel();
                        }
                        throw new StackOverflowError("框架层 Error");
                    }
                }, scheduler));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinLoop.class);
        logger.addAppender(appender);
        try {
            Thread worker = Thread.ofVirtual().start(() -> loop.run(token, config(), ctx -> {
            }));
            worker.join(10_000);
        } finally {
            logger.detachAppender(appender);
        }

        String logs = logs(appender);
        assertTrue(failures.get() >= 2, "框架层 Error 后 worker 必须继续跑下一轮: " + logs);
        assertTrue(logs.contains("consecutiveErrors=1"), "框架层 Error 必须计入连续失败: " + logs);
    }

    /**
     * 循环自身的代码出错（这里用缺 jitter 参数的配置，在 task started 行的生效配置摘要取数时踩 NPE）：
     * worker 线程结束，日志留 ERROR 与"异常退出"标记，与正常停机区分
     */
    @Test
    void abnormalExitIsLoggedWhenErrorEscapesRoundTolerance() throws Exception {
        scheduler = Executors.newScheduledThreadPool(1);
        CancellationToken token = new CancellationToken();
        RoundRobinLoop loop = new RoundRobinLoop(new RoundRobinTick(new HeldLockClient(), scheduler));
        // randomDelayMin=null：validate() 会拦住真实配置，此处刻意构造以在生效配置摘要取数时抛错
        RoundRobinConfig broken = RoundRobinConfig.builder()
                .name("broken-task")
                .frequency(Duration.ofMillis(50))
                .randomDelayMax(Duration.ofMillis(60))
                .wakeUpInterval(Duration.ofMillis(50))
                .execLockTtl(Duration.ofSeconds(30))
                .execWatchdog(true)
                .lockWait(Duration.ZERO)
                .shutdownAwait(Duration.ofSeconds(1))
                .build();

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinLoop.class);
        logger.addAppender(appender);
        try {
            Thread worker = Thread.ofVirtual().start(() -> loop.run(token, broken, ctx -> {
            }));
            worker.join(10_000);
            assertFalse(worker.isAlive(), "循环代码出错后 worker 必须退出（不得静默续跑或挂死）");
        } finally {
            logger.detachAppender(appender);
        }

        String logs = logs(appender);
        assertTrue(logs.contains("轮询循环异常退出"), "异常退出必须记 ERROR 并写明后果: " + logs);
        assertTrue(logs.contains("（异常退出）"), "停机日志形态必须与正常停机可区分: " + logs);
    }

    /** 收集日志：消息 + 顶层异常类/信息 */
    private static String logs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(event -> {
            String message = event.getFormattedMessage();
            if (event.getThrowableProxy() != null) {
                message += " | " + event.getThrowableProxy().getClassName()
                        + ": " + event.getThrowableProxy().getMessage();
            }
            return message;
        }).collect(Collectors.joining("\n"));
    }

    /** 构造 RoundRobin 测试配置 */
    private static RoundRobinConfig config() {
        return RoundRobinConfig.builder()
                .name("failing-task")
                .frequency(Duration.ofMillis(50))
                .randomDelayMin(Duration.ofMillis(50))
                .randomDelayMax(Duration.ofMillis(60))
                .wakeUpInterval(Duration.ofMillis(50))
                .execLockTtl(Duration.ofSeconds(30))
                .execWatchdog(true)
                .lockWait(Duration.ZERO)
                .shutdownAwait(Duration.ofSeconds(30))
                .build();
    }

    /** 锁客户端桩：锁一直视为持有，不触达 Redis */
    static class HeldLockClient extends RoundRobinLockClient {

        HeldLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinConfig config) {
            return true;
        }

        @Override
        public RLock tryAcquireExecution(RoundRobinConfig config) {
            return (RLock) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RLock.class},
                    (proxy, method, args) -> null);
        }

        @Override
        public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
            return true;
        }

        @Override
        public UnlockOutcome unlockQuietly(RoundRobinConfig config, RLock lock) {
            // 测试桩：不触达 Redis
            return UnlockOutcome.RELEASED;
        }
    }

}
