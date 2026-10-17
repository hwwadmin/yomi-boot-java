package com.yomi.boot.java.component.roundrobin;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundRobinTaskLogSummaryTest {

    @Test
    void logSummaryContainsKeyFields() {
        RoundRobinTask task = RoundRobin.task("demo", Duration.ofSeconds(5), ctx -> {
        });
        String summary = task.toLogSummary();
        assertTrue(summary.contains("interval=PT5S"));
        assertTrue(summary.contains("jitterAfterSuccess="));
        assertTrue(summary.contains("execLockTtl=看门狗续期"), "默认续期模式应在摘要中可辨识: " + summary);
    }

    @Test
    void logSummaryMarksHardLease() {
        RoundRobinTask task = RoundRobin.task("demo", Duration.ofSeconds(5))
                .execLockTtl(Duration.ofSeconds(30))
                .handler(ctx -> {
                })
                .build();
        assertTrue(task.toLogSummary().contains("execLockTtl=PT30S(硬租约)"), task.toLogSummary());
    }

    /** 任务简介是启动日志里唯一说明"这任务干什么"的信息，必须打出来且去掉首尾空白 */
    @Test
    void logSummaryPrintsTaskDescription() {
        RoundRobinTask task = RoundRobin.task("demo", " 清理超时订单 ", Duration.ofSeconds(5), ctx -> {
        });

        assertTrue(task.toLogSummary().contains("description=清理超时订单"), task.toLogSummary());
    }

    /** 未填写时打印占位符"未填写"（空白分不清是"没写"还是"打漏了"） */
    @Test
    void blankDescriptionPrintsPlaceholder() {
        RoundRobinTask task = RoundRobin.task("demo", "   ", Duration.ofSeconds(5), ctx -> {
        });

        assertTrue(task.toLogSummary().contains("description=未填写"), task.toLogSummary());
    }

}
