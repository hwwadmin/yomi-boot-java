package com.yomi.boot.java.component.idempotency.stale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.idempotency.config.IdempotencyProperties;
import com.yomi.boot.java.component.idempotency.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记录清理：分批循环（容量 = 单轮上限，不再受块大小封顶）、单轮行数精确封顶、两类独立预算、
 * 落后度量（最旧一行过期多久，0=追上）、配置校验。全离线：仓储为覆盖式桩，删除行数可编排。
 */
class IdempotencyStaleCleanerTest {

    @Test
    void loopsUntilCategoryEmptied() {
        StubRepository repository = new StubRepository();
        repository.expiredResults.addAll(List.of(1000, 1000, 37, 0));

        cleaner(repository, props -> {
            props.setChunkSize(1000);
            props.setMaxDeletePerRun(50_000);
        }).clean();

        assertEquals(List.of(1000, 1000, 1000, 1000), repository.expiredLimits,
                "满块继续、短块也继续（并发下短块不等于删空），直到某块删 0 行才收工");
    }

    @Test
    void stopsAtMaxDeletePerRunWithNarrowedLastBlock() {
        StubRepository repository = new StubRepository();
        repository.expiredResults.addAll(List.of(400, 400, 200, 400));

        cleaner(repository, props -> {
            props.setChunkSize(400);
            props.setMaxDeletePerRun(1000);
        }).clean();

        assertEquals(List.of(400, 400, 200), repository.expiredLimits,
                "末块按剩余额度收窄到 200，单轮精确封顶 1000 后不再删");
    }

    @Test
    void categoriesHaveIndependentBudgets() {
        StubRepository repository = new StubRepository();
        repository.staleResults.addAll(List.of(1000));       // 僵死一类一轮用完自己的额度
        repository.expiredResults.addAll(List.of(600, 400)); // 过期一类独立拿到自己的额度

        cleaner(repository, props -> {
            props.setChunkSize(1000);
            props.setMaxDeletePerRun(1000);
        }).clean();

        assertEquals(List.of(1000), repository.staleLimits, "僵死一类按自己的单轮上限封顶");
        assertEquals(List.of(1000, 400), repository.expiredLimits, "过期一类的额度不受僵死一类占用影响");
    }

    @Test
    void lagIsZeroWhenCaughtUpAndGrowsWhenOverdue() {
        StubRepository caughtUp = new StubRepository();
        caughtUp.oldestExpireTime = Instant.now().plus(Duration.ofDays(6));
        IdempotencyStaleCleaner clean = cleaner(caughtUp, props -> { });
        clean.clean();
        assertEquals(0, clean.getLagSeconds(), "剩余行都还没过期（表空同理）→ 落后 0");

        StubRepository behind = new StubRepository();
        behind.oldestExpireTime = Instant.now().minus(Duration.ofHours(30));
        IdempotencyStaleCleaner lagging = cleaner(behind, props -> { });
        lagging.clean();
        assertEquals(Duration.ofHours(30).toSeconds(), lagging.getLagSeconds(), 10,
                "最旧一行已过期 30 小时 → 落后 ≈30 小时");
    }

    @Test
    void chunkSizeAndMaxDeletePerRunGuards() {
        IdempotencyProperties properties = enabledProps();
        properties.setStaleCleanerEnabled(true);

        properties.setChunkSize(0);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setChunkSize(10_001);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setChunkSize(1000);

        properties.setMaxDeletePerRun(999);
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("不能小于 chunk-size"), ex.getMessage());
        properties.setMaxDeletePerRun(1000);
        properties.validate();
    }

    /** 日志级别口径：正常一轮只出 INFO；只有触顶（需要干预）才 WARN */
    @Test
    void normalRoundLogsInfoAndOnlyCapHitLogsWarn() {
        StubRepository repository = new StubRepository();
        repository.expiredResults.addAll(List.of(3, 0));

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            cleaner(repository, props -> {
                props.setChunkSize(1000);
                props.setMaxDeletePerRun(50_000);
            }).clean();
        } finally {
            detachLogs(logs);
        }

        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("记录清理")), "正常清理必须留 INFO 摘要: " + messages(logs));
        assertEquals(List.of(), warnMessages(logs), "正常清理不得 WARN（长期刷 WARN 会淹掉真告警）");
    }

    /** 触顶才是需要干预的情况：留 WARN 并给出去路 */
    @Test
    void capHitLogsWarnWithGuidance() {
        StubRepository repository = new StubRepository();
        repository.expiredResults.addAll(List.of(500, 500));

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            cleaner(repository, props -> {
                props.setChunkSize(500);
                props.setMaxDeletePerRun(1000);
            }).clean();
        } finally {
            detachLogs(logs);
        }

        assertTrue(warnMessages(logs).stream().anyMatch(msg -> msg.contains("达到单轮上限")
                        && msg.contains("调大上限或调小 scan-interval")),
                "触顶必须留 WARN 且指出路: " + messages(logs));
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(IdempotencyStaleCleaner.class)).addAppender(appender);
        return appender;
    }

    private static void detachLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(IdempotencyStaleCleaner.class)).detachAppender(appender);
    }

    private static List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(event -> event.getLevel() + ":" + event.getFormattedMessage())
                .toList().toString();
    }

    /** 构造清理器（配置校验不经过 validate，直接给闭合取值） */
    private static IdempotencyStaleCleaner cleaner(StubRepository repository,
                                                  java.util.function.Consumer<IdempotencyProperties> customizer) {
        IdempotencyProperties properties = enabledProps();
        properties.setStaleCleanerEnabled(true);
        customizer.accept(properties);
        return new IdempotencyStaleCleaner(repository, properties);
    }

    /** 组件启用态的最小配置 */
    private static IdempotencyProperties enabledProps() {
        IdempotencyProperties properties = new IdempotencyProperties();
        properties.setEnabled(true);
        return properties;
    }

    /** 覆盖式仓储桩：删除行数按编排返回，并记录每次下发的 limit */
    static class StubRepository extends IdempotencyRecordRepository {

        final Deque<Integer> staleResults = new ArrayDeque<>();
        final Deque<Integer> expiredResults = new ArrayDeque<>();
        final List<Integer> staleLimits = new ArrayList<>();
        final List<Integer> expiredLimits = new ArrayList<>();
        Instant oldestExpireTime;

        StubRepository() {
            super(null);
        }

        @Override
        public long deleteStaleProcessing(Instant before, int limit) {
            staleLimits.add(limit);
            return staleResults.isEmpty() ? 0 : staleResults.poll();
        }

        @Override
        public long deleteExpired(Instant before, int limit) {
            expiredLimits.add(limit);
            return expiredResults.isEmpty() ? 0 : expiredResults.poll();
        }

        @Override
        public Instant findOldestExpireTime() {
            return oldestExpireTime;
        }
    }

}
