package com.yomi.boot.java.component.roundrobin.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundRobinConfigLogSummaryTest {

    @Test
    void logSummaryContainsKeyFields() {
        RoundRobinConfig cfg = RoundRobinConfig.simple("demo", Duration.ofSeconds(5), RoundRobinDefaults.builder().build());
        String summary = cfg.toLogSummary();
        assertTrue(summary.contains("frequency=PT5S"));
        assertTrue(summary.contains("execWatchdog=true"));
        assertTrue(summary.contains("jitterAfterSuccess="));
    }

    /** 任务简介是启动日志里唯一说明"这任务干什么"的信息，必须打出来且去掉首尾空白 */
    @Test
    void logSummaryPrintsTaskDescription() {
        RoundRobinConfig cfg = RoundRobinConfig.simple("demo", " 清理超时订单 ", Duration.ofSeconds(5),
                RoundRobinDefaults.builder().build());

        assertTrue(cfg.toLogSummary().contains("description=清理超时订单"), cfg.toLogSummary());
    }

    /** 未填写时打印占位符而非空白（空白看不出是"没写"还是"打漏了"） */
    @Test
    void blankDescriptionPrintsPlaceholder() {
        RoundRobinConfig cfg = RoundRobinConfig.simple("demo", "   ", Duration.ofSeconds(5),
                RoundRobinDefaults.builder().build());

        assertTrue(cfg.toLogSummary().contains("description=未填写"), cfg.toLogSummary());
    }

}
