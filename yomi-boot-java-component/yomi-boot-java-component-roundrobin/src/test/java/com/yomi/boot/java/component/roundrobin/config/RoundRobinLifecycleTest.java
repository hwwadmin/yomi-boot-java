package com.yomi.boot.java.component.roundrobin.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.roundrobin.RoundRobin;
import com.yomi.boot.java.component.roundrobin.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinLoop;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动日志契约：每个任务由 worker 线程一行打出 task started + 生效配置，运维据此知道任务在做什么
 */
class RoundRobinLifecycleTest {

    @Test
    void startLogsDescriptionForEachTask() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry();
        register(registry, "任务甲", "清理超时订单");
        register(registry, "任务乙", null);

        RoundRobinLifecycle lifecycle = new RoundRobinLifecycle(registry, new IdleLockClient());

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        // 生效配置随 worker 线程的 task started 行输出，汇总行仍在生命周期线程：两个 logger 都收
        Logger lifecycleLogger = (Logger) LoggerFactory.getLogger(RoundRobinLifecycle.class);
        Logger loopLogger = (Logger) LoggerFactory.getLogger(RoundRobinLoop.class);
        lifecycleLogger.addAppender(appender);
        loopLogger.addAppender(appender);
        try {
            lifecycle.start();
            assertTrue(awaitUntil(() -> {
                String logs = logs(appender);
                return logs.contains("RoundRobin[任务甲] task started, 生效配置: description=清理超时订单")
                        && logs.contains("RoundRobin[任务乙] task started, 生效配置: description=未填写");
            }), "每个任务的生效配置必须随 task started 一行打出: " + logs(appender));
        } finally {
            lifecycle.stop();
            lifecycleLogger.detachAppender(appender);
            loopLogger.detachAppender(appender);
        }

        assertTrue(logs(appender).contains("yomi.roundrobin 已启动 2 个任务"), logs(appender));
    }

    /** 向 RoundRobin 注册测试任务 */
    private static void register(RoundRobinRegistry registry, String name, String description) {
        registry.register(RoundRobin.task(name, description, Duration.ofMillis(100), ctx -> {
        }));
    }

    /** 生命周期 stop 后再 start 时，取消令牌应复位以便任务继续运行 */
    @Test
    void restartsAfterStop() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry();
        AtomicInteger runs = new AtomicInteger();
        registry.register(RoundRobin.task("可重启任务", Duration.ofMillis(50), ctx -> runs.incrementAndGet()));

        RoundRobinLifecycle lifecycle = new RoundRobinLifecycle(registry, new WorkingLockClient());
        try {
            lifecycle.start();
            assertTrue(awaitUntil(() -> runs.get() > 0), "首次启动任务应执行");

            int afterStop = runs.get();
            lifecycle.stop();
            lifecycle.start();

            assertTrue(awaitUntil(() -> runs.get() > afterStop),
                    "stop 后再 start 任务应继续执行");
        } finally {
            lifecycle.stop();
        }
    }

    /** 轮询等待条件成立 */
    private static boolean awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /** 启动之后注册的任务会被增量调度并执行 */
    @Test
    void lateRegistrationAfterStartActuallyRuns() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry();
        RoundRobinLifecycle lifecycle = new RoundRobinLifecycle(registry, new WorkingLockClient());
        AtomicInteger runs = new AtomicInteger();

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinLifecycle.class);
        logger.addAppender(appender);
        try {
            lifecycle.start();
            assertTrue(lifecycle.isRunning());

            // 运行期注册（ApplicationRunner / 懒加载 Bean / 动态任务的典型时机）
            registry.register(RoundRobin.task("运行期任务", Duration.ofMillis(50), ctx -> runs.incrementAndGet()));

            assertTrue(awaitUntil(() -> runs.get() > 0), "启动后注册的任务必须被增量调度并执行");
            String logs = logs(appender);
            assertTrue(logs.contains("运行期注册，正在增量启动"), logs);
        } finally {
            lifecycle.stop();
            logger.detachAppender(appender);
        }
    }

    /** 停止之后注册：只入表、不调度（下次 start 生效），不会出现建了调度又被取消的情况 */
    @Test
    void registrationAfterStopIsNotScheduled() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry();
        RoundRobinLifecycle lifecycle = new RoundRobinLifecycle(registry, new WorkingLockClient());
        AtomicInteger runs = new AtomicInteger();

        lifecycle.start();
        lifecycle.stop();
        registry.register(RoundRobin.task("停机后注册", Duration.ofMillis(50), ctx -> runs.incrementAndGet()));

        Thread.sleep(300);
        assertTrue(registry.contains("停机后注册"), "仍应入表，供下次 start 统一调度");
        assertTrue(runs.get() == 0, "停止状态不得建调度: runs=" + runs.get());
    }

    /** 汇总捕获到的日志行 */
    private static String logs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    /** 锁客户端桩：锁始终可得，任务能真正执行；不触达 Redis */
    static class WorkingLockClient extends RoundRobinLockClient {

        WorkingLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinTask task) {
            return true;
        }

        @Override
        public org.redisson.api.RLock tryAcquireExecution(RoundRobinTask task) {
            return (org.redisson.api.RLock) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{org.redisson.api.RLock.class}, (proxy, method, args) -> null);
        }

        @Override
        public boolean isHeldByWorkerThread(org.redisson.api.RLock lock, long workerThreadId) {
            return true;
        }

        @Override
        public RoundRobinLockClient.UnlockOutcome unlockQuietly(RoundRobinTask task, org.redisson.api.RLock lock) {
            // 测试桩：不触达 Redis
            return RoundRobinLockClient.UnlockOutcome.RELEASED;
        }
    }

    /** 锁客户端桩：频率锁始终获取不到 → 任务一次都不会真正执行，全程不触达 Redis */
    static class IdleLockClient extends RoundRobinLockClient {

        IdleLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinTask task) {
            return false;
        }
    }

}
