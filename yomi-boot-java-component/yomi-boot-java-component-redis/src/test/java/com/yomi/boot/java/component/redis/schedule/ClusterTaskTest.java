package com.yomi.boot.java.component.redis.schedule;

import com.yomi.boot.java.base.function.SpinBarrier;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群定时任务：键名约定（应用名隔离）、占位丢了/执行锁忙时静默跳过、执行失败不断调度、停机后不再执行。
 * <p>占位与执行锁都注入结果（{@link ClusterTask#tryClaim} 覆写 + Redisson 桩），离线运行
 */
class ClusterTaskTest {

    /** 键名 = yomi:schedule:{spring.application.name}:{claim|lock}:{任务名} */
    @Test
    void keysIsolateByApplicationName() {
        AtomicInteger runs = new AtomicInteger();
        runner(claimedTask("demoTask", runs::incrementAndGet), true).run(context -> {
            ClusterTask task = context.getBean(ClusterTask.class);
            assertEquals("demoTask", task.getTaskName());
            assertEquals(Duration.ofMillis(50), task.getInterval());
            assertEquals("yomi:schedule:cluster-task-test:claim:demoTask", task.getClaimKey());
            assertEquals("yomi:schedule:cluster-task-test:lock:demoTask", task.getLockKey());
            assertTrue(task.isRunning(), "容器 refresh 后任务应已启动");
        });
    }

    /** 周期执行：达到多轮后停机，停机后不再开新一轮 */
    @Test
    void executesRepeatedlyAndStopsOnShutdown() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        runner(claimedTask("demoTask", runs::incrementAndGet), true).run(context -> {
            ClusterTask task = context.getBean(ClusterTask.class);
            assertTrue(SpinBarrier.spin(() -> runs.get() >= 2, 3_000), "应在超时内执行多轮，实际: " + runs.get());

            task.stop();
            assertFalse(task.isRunning());
            int afterStop = runs.get();
            Thread.sleep(300);
            assertEquals(afterStop, runs.get(), "停机后不得再执行");
        });
    }

    /** 没抢到频率占位（别的实例跑本轮）：静默跳过，不执行、不中断调度 */
    @Test
    void skipsQuietlyWhenClaimLost() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ClusterTask neverClaimed = new ClusterTask("demoTask", Duration.ofMillis(50),
                runs::incrementAndGet) {
            @Override
            protected boolean tryClaim(String claimKey) {
                return false;
            }
        };
        runner(neverClaimed, true).run(context -> {
            ClusterTask task = context.getBean(ClusterTask.class);
            Thread.sleep(300);
            assertEquals(0, runs.get(), "占位丢了不得执行任务体");
            assertTrue(task.isRunning(), "跳过不是故障，调度应保持运行");
        });
    }

    /** 拿到占位但执行锁被上一轮占着：跳过（长任务不与下一轮并发） */
    @Test
    void skipsQuietlyWhenExecutionLockBusy() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        runner(claimedTask("demoTask", runs::incrementAndGet), false).run(context -> {
            ClusterTask task = context.getBean(ClusterTask.class);
            Thread.sleep(300);
            assertEquals(0, runs.get(), "执行锁忙时不得执行任务体");
            assertTrue(task.isRunning(), "跳过不是故障，调度应保持运行");
        });
    }

    /** 任务体异常：记 ERROR 后继续下一轮（异常不终止调度） */
    @Test
    void actionFailureDoesNotStopScheduling() {
        AtomicInteger attempts = new AtomicInteger();
        Runnable failingAction = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("第一次执行失败");
            }
        };
        runner(claimedTask("demoTask", failingAction), true).run(context -> assertTrue(
                SpinBarrier.spin(() -> attempts.get() >= 2, 3_000),
                "首次失败后应继续调度，实际尝试: " + attempts.get()));
    }

    /** 参数校验：任务名、间隔、执行体在构造期拦住（不依赖容器） */
    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> ClusterTask.of(" ", Duration.ofSeconds(1), () -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> ClusterTask.of("demoTask", Duration.ZERO, () -> {
        }));
        assertThrows(NullPointerException.class, () -> ClusterTask.of("demoTask", Duration.ofSeconds(1), null));
    }

    /** 演示任务：占位恒成功（绕开 Redis），执行体递增计数 */
    private static ClusterTask claimedTask(String name, Runnable action) {
        return new ClusterTask(name, Duration.ofMillis(50), action) {
            @Override
            protected boolean tryClaim(String claimKey) {
                return true;
            }
        };
    }

    private static ApplicationContextRunner runner(ClusterTask task, boolean lockAcquired) {
        return new ApplicationContextRunner()
                .withBean(SpringUtils.class)
                .withBean(RedissonClient.class, () -> redissonStub(lockAcquired))
                .withBean(ClusterTask.class, () -> task)
                .withPropertyValues("spring.application.name=cluster-task-test");
    }

    /** Redisson 桩：getLock 返回可控 RLock（tryLock 结果由参数决定），解锁无副作用 */
    private static RedissonClient redissonStub(boolean lockAcquired) {
        RLock lock = (RLock) Proxy.newProxyInstance(ClusterTaskTest.class.getClassLoader(),
                new Class<?>[]{RLock.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "tryLock" -> lockAcquired;
                    case "unlock" -> null;
                    case "toString" -> "stub-lock";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
        return (RedissonClient) Proxy.newProxyInstance(ClusterTaskTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLock" -> lock;
                    case "toString" -> "stub-redisson";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
    }

}
