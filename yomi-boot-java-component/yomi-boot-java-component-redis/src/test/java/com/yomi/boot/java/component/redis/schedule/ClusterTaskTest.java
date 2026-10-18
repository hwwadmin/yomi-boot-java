package com.yomi.boot.java.component.redis.schedule;

import com.yomi.boot.java.base.function.SpinBarrier;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群定时任务：键名约定（应用名隔离）、停机等待跟随宿主阶段超时、没抢到名额/执行锁忙时静默跳过、
 * 执行失败不断调度、停机后不再执行。
 * <p>名额与执行锁都注入结果（{@link ClusterTask#tryAcquireTurn} 覆写 + Redisson 桩），离线运行；
 * {@code SpringUtils} 是运行期取 RedissonClient 的必经链路（生产由 core 自动配置提供），桩上下文里要显式注册
 */
class ClusterTaskTest {

    /** 构造用环境：键名只看 spring.application.name（生产由 @Bean 方法注入容器的 Environment） */
    private static final Environment APP_ENV =
            new MockEnvironment().withProperty("spring.application.name", "cluster-task-test");

    /** 阶段停机配置：默认即 Boot 默认 30s（生产由 @Bean 方法注入 Boot 的 LifecycleProperties） */
    private static final LifecycleProperties PHASE_PROPERTIES = new LifecycleProperties();

    /** 键名 = yomi:schedule:{spring.application.name}:{turn|lock}:{任务名}；停机等待按 Boot 默认阶段超时推算 */
    @Test
    void keysIsolateByApplicationName() {
        AtomicInteger runs = new AtomicInteger();
        LockStub lock = new LockStub(true);
        runner(taskWithTurn("demoTask", runs::incrementAndGet), lock).run(context -> {
            ClusterTask task = context.getBean(ClusterTask.class);
            assertEquals("demoTask", task.getTaskName());
            assertEquals(Duration.ofMillis(50), task.getInterval());
            assertEquals("yomi:schedule:cluster-task-test:turn:demoTask", task.getTurnKey());
            assertEquals("yomi:schedule:cluster-task-test:lock:demoTask", task.getLockKey());
            assertEquals(Duration.ofSeconds(24), task.getShutdownAwait(),
                    "未配阶段超时 → 按 Boot 默认 30s 取 80%");
            assertTrue(task.isRunning(), "容器 refresh 后任务应已启动");
        });
    }

    /** 停机等待跟随宿主的阶段停机超时：取 80%，不超过自身上限；负值=未定义语义，构造期失败 */
    @Test
    void shutdownAwaitFollowsPhaseTimeout() {
        assertEquals(Duration.ofSeconds(12), awaitFor(Duration.ofSeconds(15)), "宿主配 15s → 12s");
        assertEquals(Duration.ofSeconds(4).plusMillis(800), awaitFor(Duration.ofSeconds(6)));
        assertEquals(Duration.ofSeconds(30), awaitFor(Duration.ofMinutes(5)), "阶段超时很大时受自身上限约束");
        assertEquals(Duration.ZERO, awaitFor(Duration.ZERO), "0 = 不留等待，随宿主");
        assertThrows(IllegalStateException.class, () -> awaitFor(Duration.ofSeconds(-1)),
                "负值属未定义语义，配错即失败");
    }

    /** 周期执行：达到多轮后停机，停机后不再开新一轮 */
    @Test
    void executesRepeatedlyAndStopsOnShutdown() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        LockStub lock = new LockStub(true);
        runner(taskWithTurn("demoTask", runs::incrementAndGet), lock).run(context -> {
            ClusterTask task = context.getBean(ClusterTask.class);
            assertTrue(SpinBarrier.spin(() -> runs.get() >= 2, 3_000), "应在超时内执行多轮，实际: " + runs.get());

            task.stop();
            assertFalse(task.isRunning());
            int afterStop = runs.get();
            Thread.sleep(300);
            assertEquals(afterStop, runs.get(), "停机后不得再执行");
        });
    }

    /** 没抢到轮次名额（别的实例跑本轮）：静默跳过，连执行锁都不碰 */
    @Test
    void skipsQuietlyWhenTurnTaken() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        LockStub lock = new LockStub(true);
        ClusterTask neverTurns = new ClusterTask("demoTask", Duration.ofMillis(50), APP_ENV, PHASE_PROPERTIES,
                runs::incrementAndGet) {
            @Override
            protected boolean tryAcquireTurn(String turnKey) {
                return false;
            }
        };
        runner(neverTurns, lock).run(context -> {
            ClusterTask task = context.getBean(ClusterTask.class);
            Thread.sleep(300);
            assertEquals(0, runs.get(), "名额没抢到不得执行任务体");
            assertEquals(0, lock.attempts.get(), "名额没抢到不应去抢执行锁");
            assertTrue(task.isRunning(), "跳过不是故障，调度应保持运行");
        });
    }

    /** 拿到名额但执行锁被上一轮占着：跳过（长任务不与下一轮并发） */
    @Test
    void skipsQuietlyWhenExecutionLockBusy() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        LockStub lock = new LockStub(false);
        runner(taskWithTurn("demoTask", runs::incrementAndGet), lock).run(context -> {
            ClusterTask task = context.getBean(ClusterTask.class);
            Thread.sleep(300);
            assertTrue(lock.attempts.get() > 0, "本节应走到抢执行锁这一步（否则用例空跑）");
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
        LockStub lock = new LockStub(true);
        runner(taskWithTurn("demoTask", failingAction), lock).run(context -> assertTrue(
                SpinBarrier.spin(() -> attempts.get() >= 2, 3_000),
                "首次失败后应继续尝试，实际尝试: " + attempts.get()));
    }

    /** 参数校验：任务名、间隔下限、环境、阶段停机配置、执行体都在构造期拦住（不依赖容器） */
    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClusterTask(" ", Duration.ofSeconds(1), APP_ENV, PHASE_PROPERTIES, () -> {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> new ClusterTask("demoTask", Duration.ofMillis(49), APP_ENV, PHASE_PROPERTIES, () -> {
                }), "低于最小间隔（50ms）必须构造期拒绝");
        assertThrows(NullPointerException.class,
                () -> new ClusterTask("demoTask", Duration.ofSeconds(1), APP_ENV, PHASE_PROPERTIES, null));
        assertThrows(NullPointerException.class,
                () -> new ClusterTask("demoTask", Duration.ofSeconds(1), null, PHASE_PROPERTIES, () -> {
                }));
        assertThrows(NullPointerException.class,
                () -> new ClusterTask("demoTask", Duration.ofSeconds(1), APP_ENV, null, () -> {
                }));
        assertDoesNotThrow(() -> new ClusterTask("demoTask", Duration.ofMillis(50), APP_ENV, PHASE_PROPERTIES,
                () -> {
                }), "最小间隔边界值（50ms）应放行");
    }

    /** 缺 spring.application.name：构造期即失败（Bean 创建被拦下，不等到调度线程起跑） */
    @Test
    void rejectsMissingApplicationName() {
        assertThrows(IllegalStateException.class,
                () -> new ClusterTask("demoTask", Duration.ofSeconds(1), new MockEnvironment(), PHASE_PROPERTIES,
                        () -> {
                        }));
    }

    /** 演示任务：名额恒能抢到（绕开 Redis），执行体是给定逻辑 */
    private static ClusterTask taskWithTurn(String name, Runnable action) {
        return new ClusterTask(name, Duration.ofMillis(50), APP_ENV, PHASE_PROPERTIES, action) {
            @Override
            protected boolean tryAcquireTurn(String turnKey) {
                return true;
            }
        };
    }

    /** 指定阶段停机超时构造的任务：返回其算定的停机等待 */
    private static Duration awaitFor(Duration phaseTimeout) {
        LifecycleProperties properties = new LifecycleProperties();
        properties.setTimeoutPerShutdownPhase(phaseTimeout);
        return new ClusterTask("demoTask", Duration.ofSeconds(1), APP_ENV, properties, () -> {
        }).getShutdownAwait();
    }

    private static ApplicationContextRunner runner(ClusterTask task, LockStub lock) {
        return new ApplicationContextRunner()
                .withBean(SpringUtils.class)
                .withBean(RedissonClient.class, lock::client)
                .withBean(ClusterTask.class, () -> task);
    }

    /** Redisson 桩：可设 tryLock 结果，并记录抢锁尝试次数（区分"没走到执行锁"与"走到了但锁忙"） */
    private static final class LockStub {

        private final boolean acquired;

        /** 抢执行锁的尝试次数 */
        private final AtomicInteger attempts = new AtomicInteger();

        LockStub(boolean acquired) {
            this.acquired = acquired;
        }

        RedissonClient client() {
            RLock lock = (RLock) Proxy.newProxyInstance(ClusterTaskTest.class.getClassLoader(),
                    new Class<?>[]{RLock.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "tryLock" -> {
                            attempts.incrementAndGet();
                            yield acquired;
                        }
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

}
