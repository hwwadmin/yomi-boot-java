package com.yomi.boot.java.component.roundrobin.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册表：同名注册原子拒绝、启动后注册触发补建回调。
 */
class RoundRobinRegistryTest {

    @Test
    void lateRegistrationTriggersHandlerAfterStart() {
        RoundRobinRegistry registry = new RoundRobinRegistry();
        List<String> delivered = new ArrayList<>();
        registry.setLateRegistrationHandler(definition -> delivered.add(definition.getConfig().getName()));

        registry.register(task("任务A"));

        assertEquals(List.of("任务A"), delivered, "启动后注册必须交给 lifecycle 立刻补建调度");
    }

    @Test
    void registrationWithoutHandlerStaysSilent() {
        RoundRobinRegistry registry = new RoundRobinRegistry();

        registry.register(task("任务A"));

        assertTrue(registry.contains("任务A"), "没有 lifecycle（如单元测试）时注册照常入表");
    }

    @Test
    void concurrentDuplicateRegistrationKeepsExactlyOne() throws Exception {
        RoundRobinRegistry registry = new RoundRobinRegistry();
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
        RoundRobinRegistry registry = new RoundRobinRegistry();
        registry.register(task("任务A"));

        assertThrows(IllegalStateException.class, () -> registry.register(task("任务A")));
    }

    private static RoundRobinTaskDefinition task(String name) {
        return new RoundRobinTaskDefinition(
                RoundRobinConfig.simple(name, Duration.ofSeconds(1), RoundRobinDefaults.builder().build()),
                ctx -> {
                });
    }

}
