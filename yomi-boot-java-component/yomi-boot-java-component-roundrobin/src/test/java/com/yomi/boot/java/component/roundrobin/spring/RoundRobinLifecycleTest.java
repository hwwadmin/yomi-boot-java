package com.yomi.boot.java.component.roundrobin.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinDefaults;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinTaskDefinition;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动日志契约：每个任务打印任务名 + 作用简介，否则运维只看到任务名、不知道它在做什么
 */
class RoundRobinLifecycleTest {

    private ScheduledExecutorService scheduler;

    @AfterEach
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void startLogsDescriptionForEachTask() {
        scheduler = Executors.newScheduledThreadPool(1);
        RoundRobinRegistry registry = new RoundRobinRegistry();
        register(registry, "任务甲", "清理超时订单");
        register(registry, "任务乙", null);

        RoundRobinLifecycle lifecycle = new RoundRobinLifecycle(registry, new IdleLockClient(), scheduler);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinLifecycle.class);
        logger.addAppender(appender);
        try {
            lifecycle.start();
        } finally {
            lifecycle.stop();
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertTrue(logs.contains("RoundRobin[任务甲] 生效配置: description=清理超时订单"), logs);
        assertTrue(logs.contains("RoundRobin[任务乙] 生效配置: description=未填写"), logs);
        assertTrue(logs.contains("yomi.roundrobin 已启动 2 个任务"), logs);
    }

    /** 向 RoundRobin 注册测试任务 */
    private static void register(RoundRobinRegistry registry, String name, String description) {
        RoundRobinConfig config = RoundRobinConfig.simple(name, description, Duration.ofMillis(100),
                RoundRobinDefaults.builder().build());
        registry.register(new RoundRobinTaskDefinition(config, ctx -> {
        }));
    }

    /** 生命周期 stop 后再 start 时，取消令牌应复位以便任务继续运行 */
    @Test
    void restartsAfterStop() throws Exception {
        scheduler = Executors.newScheduledThreadPool(1);
        RoundRobinRegistry registry = new RoundRobinRegistry();
        AtomicInteger runs = new AtomicInteger();
        RoundRobinConfig config = RoundRobinConfig.simple("可重启任务", Duration.ofMillis(50),
                RoundRobinDefaults.builder().build());
        registry.register(new RoundRobinTaskDefinition(config, ctx -> runs.incrementAndGet()));

        RoundRobinLifecycle lifecycle = new RoundRobinLifecycle(registry, new WorkingLockClient(), scheduler);
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

    /** 启动之后注册的任务必须真的被调度（历史缺陷：注册成功却永不执行，且无任何日志） */
    @Test
    void lateRegistrationAfterStartActuallyRuns() throws Exception {
        scheduler = Executors.newScheduledThreadPool(1);
        RoundRobinRegistry registry = new RoundRobinRegistry();
        RoundRobinLifecycle lifecycle = new RoundRobinLifecycle(registry, new WorkingLockClient(), scheduler);
        AtomicInteger runs = new AtomicInteger();

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinLifecycle.class);
        logger.addAppender(appender);
        try {
            lifecycle.start();
            assertTrue(lifecycle.isRunning());

            // 运行期注册（ApplicationRunner / 懒加载 Bean / 动态任务的典型时机）
            registry.register(new RoundRobinTaskDefinition(
                    RoundRobinConfig.simple("运行期任务", Duration.ofMillis(50), RoundRobinDefaults.builder().build()),
                    ctx -> runs.incrementAndGet()));

            assertTrue(awaitUntil(() -> runs.get() > 0), "启动后注册的任务必须被增量调度并执行");
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logs.contains("运行期注册，已增量启动"), logs);
        } finally {
            lifecycle.stop();
            logger.detachAppender(appender);
        }
    }

    /** 停止之后注册：入表但不调度（下次 start 生效），不得在建了调度后立刻又被取消 */
    @Test
    void registrationAfterStopIsNotScheduled() throws Exception {
        scheduler = Executors.newScheduledThreadPool(1);
        RoundRobinRegistry registry = new RoundRobinRegistry();
        RoundRobinLifecycle lifecycle = new RoundRobinLifecycle(registry, new WorkingLockClient(), scheduler);
        AtomicInteger runs = new AtomicInteger();

        lifecycle.start();
        lifecycle.stop();
        registry.register(new RoundRobinTaskDefinition(
                RoundRobinConfig.simple("停机后注册", Duration.ofMillis(50), RoundRobinDefaults.builder().build()),
                ctx -> runs.incrementAndGet()));

        Thread.sleep(300);
        assertTrue(registry.contains("停机后注册"), "仍应入表，供下次 start 统一调度");
        assertTrue(runs.get() == 0, "停止状态不得建调度: runs=" + runs.get());
    }

    /** 锁客户端桩：锁始终可得，任务能真正执行；不触达 Redis */
    static class WorkingLockClient extends RoundRobinLockClient {

        WorkingLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinConfig config) {
            return true;
        }

        @Override
        public org.redisson.api.RLock tryAcquireExecution(RoundRobinConfig config) {
            return (org.redisson.api.RLock) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{org.redisson.api.RLock.class}, (proxy, method, args) -> null);
        }

        @Override
        public boolean isHeldByWorkerThread(org.redisson.api.RLock lock, long workerThreadId) {
            return true;
        }

        @Override
        public RoundRobinLockClient.UnlockOutcome unlockQuietly(RoundRobinConfig config, org.redisson.api.RLock lock) {
            // 测试桩：不触达 Redis
            return RoundRobinLockClient.UnlockOutcome.RELEASED;
        }
    }

    /** 锁客户端桩：频率锁永远抢不到 → 任务一次都不会真正执行，全程不触达 Redis */
    static class IdleLockClient extends RoundRobinLockClient {

        IdleLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinConfig config) {
            return false;
        }
    }

}
