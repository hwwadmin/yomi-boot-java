package com.yomi.boot.java.component.event.retention;

import com.yomi.boot.java.component.event.config.EventProperties;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.repository.EventInboxRepository;
import com.yomi.boot.java.component.event.repository.EventOutboxRepository;
import com.yomi.boot.java.component.event.repository.mapper.EventInboxMapper;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxMapper;
import com.yomi.boot.java.component.event.support.EventKeys;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 保留期清理：分批循环、单轮上限、两侧隔离、配置校验。
 * <p>全离线：Mapper 为记录型代理，返回可编排的删除行数</p>
 */
class EventRetentionCleanerTest {

    private static final EventKeys EVENT_KEYS = new EventKeys("test-service");

    @Test
    void outboxDeletesBatchUntilLessThanBatchSize() {
        Deque<Integer> results = new ArrayDeque<>(List.of(2, 2, 0));
        OutboxCalls calls = new OutboxCalls(results);
        EventProperties properties = retentionProps(r -> {
            r.setBatchSize(2);
            r.setOutboxRetention(Duration.ofDays(3));
        });

        new EventRetentionCleaner(properties, EVENT_KEYS, new EventOutboxRepository(calls.mapper()), null)
                .cleanOutbox();

        assertEquals(3, calls.invocations.size(), "2,2 满批继续，0 < 批次即停");
        assertEquals(List.of(2, 2, 2), calls.limits);
        assertTrue(calls.serviceNames.stream().allMatch("test-service"::equals), "只清理当前服务行");
        assertTrue(calls.statuses.stream().allMatch(s -> s == OutboxStatus.SENT_SUCCESS), "只允许删已成功终态的行");
        Instant expectedBefore = Instant.now().minus(Duration.ofDays(3));
        for (Instant before : calls.beforeTimes) {
            assertTrue(Math.abs(Duration.between(expectedBefore, before).toSeconds()) < 10,
                    "删除阈值应为 当前时间 - outbox-retention: " + before);
        }
    }

    @Test
    void outboxStopsAtBatchBoundary() {
        Deque<Integer> results = new ArrayDeque<>(List.of(2, 1, 2));
        OutboxCalls calls = new OutboxCalls(results);
        EventProperties properties = retentionProps(r -> r.setBatchSize(2));

        new EventRetentionCleaner(properties, EVENT_KEYS, new EventOutboxRepository(calls.mapper()), null)
                .cleanOutbox();

        assertEquals(2, calls.invocations.size(), "删到不足一批时应结束本轮清理");
    }

    @Test
    void outboxRespectsMaxDeletePerRun() {
        Deque<Integer> results = new ArrayDeque<>(List.of(1, 1, 1, 1, 1));
        OutboxCalls calls = new OutboxCalls(results);
        EventProperties properties = retentionProps(r -> {
            r.setBatchSize(1);
            r.setMaxDeletePerRun(3);
        });

        new EventRetentionCleaner(properties, EVENT_KEYS, new EventOutboxRepository(calls.mapper()), null)
                .cleanOutbox();

        assertEquals(3, calls.invocations.size(), "达到单轮上限即停，剩余行留在下轮");
    }

    @Test
    void inboxUsesInboxRetentionAndAbsentSideIsNoOp() {
        Deque<Integer> results = new ArrayDeque<>(List.of(0));
        List<Instant> beforeTimes = new ArrayList<>();
        EventProperties properties = retentionProps(r -> {
            r.setBatchSize(100);
            r.setInboxRetention(Duration.ofDays(5));
        });
        EventInboxMapper mapper = (EventInboxMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{EventInboxMapper.class}, (proxy, method, args) -> {
                    if ("deleteCreatedBefore".equals(method.getName())) {
                        assertEquals("test-service", args[0]);
                        beforeTimes.add((Instant) args[1]);
                        return results.isEmpty() ? 0 : results.poll();
                    }
                    return null;
                });

        EventRetentionCleaner cleaner = new EventRetentionCleaner(properties, EVENT_KEYS, null,
                new EventInboxRepository(mapper));
        assertDoesNotThrow(() -> cleaner.cleanOutbox(), "未传发件箱仓储时不应有动作/报错");
        cleaner.cleanInbox();

        assertEquals(1, beforeTimes.size());
        Instant expectedBefore = Instant.now().minus(Duration.ofDays(5));
        assertTrue(Math.abs(Duration.between(expectedBefore, beforeTimes.get(0)).toSeconds()) < 10,
                "删除阈值应为 当前时间 - inbox-retention");
    }

    @Test
    void inboxAbsentSideIsNoOp() {
        EventProperties properties = retentionProps(r -> { });
        EventRetentionCleaner cleaner = new EventRetentionCleaner(properties, EVENT_KEYS, null, null);

        assertDoesNotThrow(() -> cleaner.cleanInbox(), "未传收件箱仓储时不应有动作/报错");
    }

    /** 收件箱清理独立开关：不要求发件箱开关同时打开 */
    @Test
    void inboxRetentionSwitchIsIndependentOfOutbox() {
        EventProperties properties = new EventProperties();
        properties.setConsumerEnabled(true);
        properties.getRetention().setInboxRetentionEnabled(true);

        assertDoesNotThrow(properties::validate, "只开收件箱清理也是合法配置");
    }

    @Test
    void inboxRetentionBelowOneDayIsRejected() {
        EventProperties properties = new EventProperties();
        properties.setPublisherEnabled(true);
        properties.getRetention().setInboxRetentionEnabled(true);
        properties.getRetention().setInboxRetention(Duration.ofHours(12));

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("inbox-retention 必须大于等于 1 天"), ex.getMessage());
    }

    @Test
    void inboxRetentionBelowSevenDaysWarnsButPasses() {
        EventProperties properties = new EventProperties();
        properties.setPublisherEnabled(true);
        properties.getRetention().setInboxRetentionEnabled(true);
        properties.getRetention().setInboxRetention(Duration.ofDays(3));

        assertDoesNotThrow(properties::validate, "3 天只告警不阻断（需运维确认 topic retention）");
    }

    @Test
    void retentionNumericGuards() {
        EventProperties properties = new EventProperties();
        properties.setPublisherEnabled(true);
        properties.getRetention().setOutboxRetentionEnabled(true);

        properties.getRetention().setScanInterval(Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, properties::validate);
        properties.getRetention().setScanInterval(Duration.ofHours(1));

        properties.getRetention().setBatchSize(0);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.getRetention().setBatchSize(1000);

        properties.getRetention().setMaxDeletePerRun(0);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.getRetention().setMaxDeletePerRun(50_000);

        properties.getRetention().setOutboxRetention(Duration.ZERO);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    /** 构造保留期清理配置 */
    private static EventProperties retentionProps(java.util.function.Consumer<EventProperties.Retention> customizer) {
        EventProperties properties = new EventProperties();
        properties.setPublisherEnabled(true);
        EventProperties.Retention retention = properties.getRetention();
        retention.setOutboxRetentionEnabled(true);
        retention.setMaxDeletePerRun(1000);
        customizer.accept(retention);
        return properties;
    }

    /** 记录 deleteSentSuccessBefore 的调用参数 */
    private static final class OutboxCalls {

        private final Deque<Integer> results;
        private final List<Object[]> invocations = new ArrayList<>();
        private final List<String> serviceNames = new ArrayList<>();
        private final List<OutboxStatus> statuses = new ArrayList<>();
        private final List<Integer> limits = new ArrayList<>();
        private final List<Instant> beforeTimes = new ArrayList<>();

        private OutboxCalls(Deque<Integer> results) {
            this.results = results;
        }

        /** 构造 EventRetention Mapper 桩 */
        private EventOutboxMapper mapper() {
            return (EventOutboxMapper) Proxy.newProxyInstance(EventRetentionCleanerTest.class.getClassLoader(),
                    new Class<?>[]{EventOutboxMapper.class}, (proxy, method, args) -> {
                        if ("deleteSentSuccessBefore".equals(method.getName())) {
                            invocations.add(args);
                            serviceNames.add((String) args[0]);
                            statuses.add((OutboxStatus) args[1]);
                            beforeTimes.add((Instant) args[2]);
                            limits.add((Integer) args[3]);
                            return results.isEmpty() ? 0 : results.poll();
                        }
                        return null;
                    });
        }
    }

}
