package com.yomi.boot.java.component.roundrobin.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 每轮观测器：轮内基准（首跳 -1，不复用上一轮时点）、固定节奏、真实间隔测量、stop 非阻塞且不再回调
 */
class RoundRobinWatchTest {

    @Test
    void ticksWithPerRoundBaselineAndStopsCleanly() throws Exception {
        List<Long> gaps = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> threadName = new AtomicReference<>();
        RoundRobinWatch watch = RoundRobinWatch.start("demo", gap -> {
            threadName.compareAndSet(null, Thread.currentThread().getName());
            gaps.add(gap);
        });
        try {
            assertTrue(awaitUntil(() -> gaps.size() >= 3), "1 秒内应至少观测 3 次: " + gaps);
        } finally {
            watch.stop();
        }

        int afterStop = gaps.size();
        Thread.sleep(Duration.ofMillis(200));
        assertEquals(afterStop, gaps.size(), "stop() 后不得再有观测: " + gaps);
        assertEquals(-1L, gaps.get(0), "本轮首次观测必须是 -1（轮内基准，不复用上一轮时点）: " + gaps);
        assertTrue(gaps.get(1) > 0, "第二次观测应带正间隔: " + gaps);
        assertEquals("roundrobin-watch-demo", threadName.get(), "线程名应便于 jstack 识别");
    }

    /** 探测被拖慢时，实测间隔反映真实耗时（延迟告警的数据来源） */
    @Test
    void measuresRealGapWhenCallbackIsSlow() throws Exception {
        List<Long> gaps = Collections.synchronizedList(new ArrayList<>());
        RoundRobinWatch watch = RoundRobinWatch.start("slow", gap -> {
            gaps.add(gap);
            if (gaps.size() == 2) {
                try {
                    Thread.sleep(300);      // 模拟一次慢探测
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            assertTrue(awaitUntil(() -> gaps.size() >= 3), "慢探测后仍应继续观测: " + gaps);
        } finally {
            watch.stop();
        }

        assertTrue(gaps.get(2) >= Duration.ofMillis(300).toNanos(),
                "第三次观测的间隔应反映被拖住的真实耗时: " + gaps);
    }

    /** 启动后立即 stop（首次观测尚未发生）不得再有回调；stop 幂等 */
    @Test
    void stopBeforeFirstTickIsSafeAndIdempotent() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        RoundRobinWatch watch = RoundRobinWatch.start("early", gap -> ticks.incrementAndGet());

        watch.stop();
        watch.stop();

        Thread.sleep(Duration.ofMillis(200));
        assertEquals(0, ticks.get(), "立即 stop 后不得再有观测");
    }

    private static boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

}
