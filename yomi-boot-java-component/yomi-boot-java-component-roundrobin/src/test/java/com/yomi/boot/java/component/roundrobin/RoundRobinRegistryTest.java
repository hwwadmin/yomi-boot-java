package com.yomi.boot.java.component.roundrobin;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinLoop;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注册表：登记与同名去重；组件启动后为已登记任务创建调度实例；运行期注册立即补建实例；停机统一回收。
 * <p>注册与启停共用监视器——"注册 → 建实例"没有中间窗口（并发注册 × 启动的用例锁定该不变量）。
 */
class RoundRobinRegistryTest {

    @Test
    void registrationWithoutStartStaysSilent() {
        RoundRobinRegistry registry = new RoundRobinRegistry(new IdleLockClient());

        registry.register(task("任务A"));

        assertTrue(registry.contains("任务A"), "未启动时注册只入表");
    }

    @Test
    void registerReturnsRegisteredTask() {
        RoundRobinRegistry registry = new RoundRobinRegistry(new IdleLockClient());
        RoundRobinTask task = task("任务A");

        assertSame(task, registry.register(task), "@Bean 方法直接返回注册结果");
    }

    @Test
    void concurrentDuplicateRegistrationKeepsExactlyOne() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry(new IdleLockClient());
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                        registry.register(task("同名任务"));
                        accepted.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            fire.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, accepted.get(), "并发同名注册只允许一个成功");
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, registry.list().size());
    }

    @Test
    void duplicateNameIsRejected() {
        RoundRobinRegistry registry = new RoundRobinRegistry(new IdleLockClient());
        registry.register(task("任务A"));

        assertThrows(IllegalStateException.class, () -> registry.register(task("任务A")));
    }

    /** 启动汇总 + 每个任务的生效配置行（由实例线程输出） */
    @Test
    void startLogsDescriptionForEachTask() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry(new IdleLockClient());
        registry.register(RoundRobin.task("任务甲", "清理超时订单", Duration.ofMillis(100), ctx -> {
        }));
        registry.register(RoundRobin.task("任务乙", null, Duration.ofMillis(100), ctx -> {
        }));

        ListAppender<ILoggingEvent> appender = attach(RoundRobinRegistry.class, RoundRobinLoop.class);
        try {
            registry.start();
            assertTrue(awaitUntil(() -> {
                String logs = logs(appender);
                return logs.contains("RoundRobin[任务甲] task started, 生效配置: description=清理超时订单")
                        && logs.contains("RoundRobin[任务乙] task started, 生效配置: description=未填写");
            }), "每个任务的生效配置必须随 task started 一行打出: " + logs(appender));
        } finally {
            registry.stop();
            detach(appender);
        }

        assertTrue(logs(appender).contains("yomi.roundrobin 已启动 2 个任务"), logs(appender));
    }

    /** stop 后再 start：取消令牌复位，实例继续运行 */
    @Test
    void restartsAfterStop() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry(new WorkingLockClient());
        AtomicInteger runs = new AtomicInteger();
        registry.register(RoundRobin.task("可重启任务", Duration.ofMillis(50), ctx -> runs.incrementAndGet()));

        try {
            registry.start();
            assertTrue(awaitUntil(() -> runs.get() > 0), "首次启动任务应执行");

            int afterStop = runs.get();
            registry.stop();
            registry.start();

            assertTrue(awaitUntil(() -> runs.get() > afterStop), "stop 后再 start 任务应继续执行");
        } finally {
            registry.stop();
        }
    }

    /** 启动之后注册：立即补建实例并执行，日志写明增量启动 */
    @Test
    void lateRegistrationAfterStartActuallyRuns() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry(new WorkingLockClient());
        AtomicInteger runs = new AtomicInteger();

        ListAppender<ILoggingEvent> appender = attach(RoundRobinRegistry.class);
        try {
            registry.start();
            assertTrue(registry.isRunning());

            // 运行期注册（ApplicationRunner / 懒加载 Bean / 动态任务的典型时机）
            registry.register(RoundRobin.task("运行期任务", Duration.ofMillis(50), ctx -> runs.incrementAndGet()));

            assertTrue(awaitUntil(() -> runs.get() > 0), "启动后注册的任务必须立即被调度并执行");
            assertTrue(logs(appender).contains("运行期注册，正在增量启动"), logs(appender));
        } finally {
            registry.stop();
            detach(appender);
        }
    }

    /** 停止之后注册：只入表不建例；下次 start 统一创建（注册不丢） */
    @Test
    void registrationAfterStopIsScheduledByNextStart() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry(new WorkingLockClient());
        AtomicInteger runs = new AtomicInteger();

        registry.start();
        registry.stop();
        registry.register(RoundRobin.task("停机后注册", Duration.ofMillis(50), ctx -> runs.incrementAndGet()));

        Thread.sleep(300);
        assertTrue(registry.contains("停机后注册"), "仍应入表，供下次 start 统一调度");
        assertEquals(0, runs.get(), "停止状态不得建实例: runs=" + runs.get());

        try {
            registry.start();
            assertTrue(awaitUntil(() -> runs.get() > 0), "下次 start 必须把停机期间注册的任务带上");
        } finally {
            registry.stop();
        }
    }

    /** 注册与启动竞争：无论谁先拿到锁，该任务都只允许有一个调度实例 */
    @Test
    void concurrentRegisterDuringStartYieldsExactlyOneInstance() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry(new IdleLockClient());
        CountDownLatch fire = new CountDownLatch(1);

        ListAppender<ILoggingEvent> appender = attach(RoundRobinRegistry.class, RoundRobinLoop.class);
        try {
            Thread starter = new Thread(() -> {
                await(fire);
                registry.start();
            });
            Thread registrar = new Thread(() -> {
                await(fire);
                registry.register(RoundRobin.task("并发任务", Duration.ofMillis(50), ctx -> {
                }));
            });
            starter.start();
            registrar.start();
            fire.countDown();
            starter.join();
            registrar.join();

            assertTrue(awaitUntil(() -> logs(appender).contains("RoundRobin[并发任务] task started")), logs(appender));
            Thread.sleep(150);      // 若误建了第二个实例，它也会打一行 task started
            long startedLines = logs(appender).lines()
                    .filter(line -> line.contains("RoundRobin[并发任务] task started")).count();
            assertEquals(1L, startedLines, "注册与启动竞争下只允许一个调度实例: " + logs(appender));
        } finally {
            registry.stop();
            detach(appender);
        }
    }

    /** 尽量晚启动（晚于所有单例创建与启动期初始化，如各组件建表）、早停止的相位 */
    @Test
    void phaseStartsAfterSingletonInitialization() {
        RoundRobinRegistry registry = new RoundRobinRegistry(new IdleLockClient());

        assertEquals(Integer.MAX_VALUE - 200, registry.getPhase(), "相位是'启动晚于建表、停机早于其它'契约的实现");
    }

    private static RoundRobinTask task(String name) {
        return RoundRobin.task(name, Duration.ofSeconds(1), ctx -> {
        });
    }

    /** 挂上与分离日志捕获 */
    private static ListAppender<ILoggingEvent> attach(Class<?>... loggers) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        for (Class<?> logger : loggers) {
            ((Logger) LoggerFactory.getLogger(logger)).addAppender(appender);
        }
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(RoundRobinRegistry.class)).detachAppender(appender);
        ((Logger) LoggerFactory.getLogger(RoundRobinLoop.class)).detachAppender(appender);
    }

    private static String logs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    private static boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 锁客户端桩：频率锁始终获取不到 → 实例不会真正执行 handler，全程不触达 Redis */
    static class IdleLockClient extends RoundRobinLockClient {

        IdleLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinTask task) {
            return false;
        }
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
        public RLock tryAcquireExecution(RoundRobinTask task) {
            return (RLock) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RLock.class},
                    (proxy, method, args) -> null);
        }

        @Override
        public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
            return true;
        }

        @Override
        public UnlockOutcome unlockQuietly(RoundRobinTask task, RLock lock) {
            // 测试桩：不触达 Redis
            return UnlockOutcome.RELEASED;
        }
    }

}
