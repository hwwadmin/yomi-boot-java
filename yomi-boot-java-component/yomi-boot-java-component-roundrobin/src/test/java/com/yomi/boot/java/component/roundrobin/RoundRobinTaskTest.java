package com.yomi.boot.java.component.roundrobin;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务定义的构造期派生与校验：派生值固定（不提供配置）、非法参数创建即失败
 */
class RoundRobinTaskTest {

    /** 派生：jitter = [100ms, 100ms+interval)，抢锁休眠 = max(interval/2, 100ms) */
    @Test
    void derivesJitterAndWakeUpFromInterval() {
        RoundRobinTask task = RoundRobin.task("demo", Duration.ofSeconds(2), ctx -> {
        });

        assertEquals(Duration.ofMillis(100), task.getJitterMin());
        assertEquals(Duration.ofSeconds(2).plusMillis(100), task.getJitterMax());
        assertEquals(Duration.ofSeconds(1), task.getWakeUpInterval());

        RoundRobinTask tiny = RoundRobin.task("tiny", Duration.ofMillis(50), ctx -> {
        });
        assertEquals(Duration.ofMillis(100), tiny.getWakeUpInterval(), "间隔过小时休眠取下限 100ms");
    }

    /** 默认：看门狗续期（execLockTtl 未配置）、maxHold 不限、lockWait=0、shutdownAwait=30s */
    @Test
    void appliesDefaults() {
        RoundRobinTask task = RoundRobin.task("demo", Duration.ofSeconds(1), ctx -> {
        });

        assertNull(task.getExecLockTtl());
        assertNull(task.getMaxHoldDuration());
        assertEquals(Duration.ZERO, task.getLockWait());
        assertEquals(Duration.ofSeconds(30), task.getShutdownAwait());
    }

    /** 任务级参数不在 yml 里：报错带任务名并指向 RoundRobin.task(...) 参数，不出现 yml 配置键 */
    @Test
    void invalidIntervalMessagePointsToTaskFactory() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RoundRobin.task("demo-task", Duration.ofMillis(10), ctx -> {
                }));

        String message = e.getMessage();
        assertTrue(message.contains("interval"), message);
        assertTrue(message.contains("demo-task"), "报错应带上任务名: " + message);
        assertTrue(message.contains("RoundRobin.task"), "报错应指向创建入口: " + message);
        assertFalse(message.contains("yomi.roundrobin."), "不应指向不存在的 yml 配置键: " + message);
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(IllegalStateException.class, () -> RoundRobin.task(" ", Duration.ofSeconds(1), ctx -> {
        }));
        assertThrows(IllegalStateException.class, () -> RoundRobin.task("含尾空格 ", Duration.ofSeconds(1), ctx -> {
        }));
    }

    @Test
    void missingHandlerIsRejected() {
        assertThrows(NullPointerException.class,
                () -> RoundRobin.task("demo", Duration.ofSeconds(1)).build());
    }

    @Test
    void negativeLockWaitIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> RoundRobin.task("demo", Duration.ofSeconds(1)).lockWait(Duration.ofMillis(-1)).build());
    }

    @Test
    void zeroShutdownAwaitIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> RoundRobin.task("demo", Duration.ofSeconds(1)).shutdownAwait(Duration.ZERO).build());
    }

    /** 硬租约：maxHold 大于租约意味着锁先过期、其它实例可能并发执行，创建期即报错 */
    @Test
    void hardLeaseRejectsMaxHoldBeyondLease() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> hardLeaseTask(Duration.ofSeconds(30), Duration.ofMinutes(1)));

        String message = e.getMessage();
        assertTrue(message.contains("maxHoldDuration 不能大于 execLockTtl"), message);
        assertTrue(message.contains("并发执行"), "报错应点明后果: " + message);
    }

    @Test
    void hardLeaseAllowsMaxHoldWithinLease() {
        assertDoesNotThrow(() -> hardLeaseTask(Duration.ofSeconds(30), Duration.ofSeconds(10)));
        assertDoesNotThrow(() -> hardLeaseTask(Duration.ofSeconds(30), Duration.ofSeconds(30)),
                "maxHold 等于租约边界值允许（软取消与硬过期同时触发）");
    }

    /** 看门狗续期（未配置 execLockTtl）时无租约可越界，maxHold 只受下限约束 */
    @Test
    void watchdogModeAllowsAnyMaxHold() {
        assertDoesNotThrow(() -> RoundRobin.task("demo", Duration.ofSeconds(1))
                .maxHoldDuration(Duration.ofSeconds(5))
                .handler(ctx -> {
                })
                .build());
    }

    /** 描述去掉首尾空白；空白视为未填写 */
    @Test
    void descriptionIsTrimmed() {
        RoundRobinTask task = RoundRobin.task("demo", " 清理超时订单 ", Duration.ofSeconds(1), ctx -> {
        });
        assertEquals("清理超时订单", task.getDescription());

        RoundRobinTask blank = RoundRobin.task("demo", "   ", Duration.ofSeconds(1), ctx -> {
        });
        assertNull(blank.getDescription());
    }

    /** 构造硬租约任务 */
    private static RoundRobinTask hardLeaseTask(Duration lease, Duration maxHold) {
        return RoundRobin.task("demo", Duration.ofSeconds(1))
                .execLockTtl(lease)
                .maxHoldDuration(maxHold)
                .handler(ctx -> {
                })
                .build();
    }

}
