package com.yomi.boot.java.component.httplog.retention;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogQuery;
import com.yomi.boot.java.component.httplog.repository.IHttpLogRepository;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留期清理语义：按 chunk-size 分批删到删空或达到单轮上限（上限按块粒度精确封顶）、
 * limit 原样下传（不夹取）、失败上抛（由集群任务记 ERROR、下轮重试）、落后度量来自最旧一行
 *
 * @author zwei
 */
class HttpLogRetentionCleanerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final FakeRepository repository = new FakeRepository();

    @Test
    void clean_deletesInChunksUntilRepositoryReturnsEmpty() {
        repository.scripted(1000, 1000, 300, 0);
        HttpLogRetentionCleaner cleaner = cleaner(1000, 200_000);

        cleaner.clean();

        // 末位那次返回 0 的调用只用于判定"删空"
        assertEquals(List.of(1000, 1000, 1000, 1000), repository.limits, "每块都按配置的 chunk-size 下传");
        assertEquals(2300.0, deleted());
    }

    @Test
    void clean_stopsAtMaxDeletePerRun_withoutOvershoot() {
        repository.scripted(1000, 1000, 1000);
        HttpLogRetentionCleaner cleaner = cleaner(1000, 2500);

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            cleaner.clean();
        } finally {
            detachLogs(logs);
        }

        assertEquals(List.of(1000, 1000, 500), repository.limits, "收尾块按剩余额度收窄，单轮不超上限");
        assertEquals(2500.0, deleted());
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("达到单轮上限")), "触顶要有告警");
    }

    @Test
    void clean_nothingExpired_stopsAfterOneProbeAndCountsNothing() {
        repository.scripted(0);
        HttpLogRetentionCleaner cleaner = cleaner(1000, 200_000);

        cleaner.clean();

        assertEquals(List.of(1000), repository.limits);
        assertNull(counter(), "没有删除就不注册计数");
    }

    @Test
    void clean_repositoryFailurePropagates() {
        repository.scripted(1000);
        repository.failure = new IllegalStateException("db down");
        HttpLogRetentionCleaner cleaner = cleaner(1000, 200_000);

        assertThrows(IllegalStateException.class, cleaner::clean, "失败上抛，由集群任务记 ERROR、下轮重试");
    }

    @Test
    void lagSeconds_emptyTableIsZero_thenTracksOldestRow() {
        repository.scripted(0);
        HttpLogRetentionCleaner cleaner = cleaner(1000, 200_000);

        cleaner.clean();
        assertEquals(0, cleaner.getLagSeconds(), "表空落后为 0");

        repository.oldest = Instant.now().minus(Duration.ofDays(8));
        cleaner.clean();
        assertEquals(Duration.ofDays(8).toSeconds(), cleaner.getLagSeconds(), 10, "落后 = now 与最旧一行之差");
    }

    @Test
    void lagGauge_readsCleanerStateWithoutHittingDatabase() {
        repository.scripted(0);
        repository.oldest = Instant.now().minus(Duration.ofDays(3));
        HttpLogRetentionCleaner cleaner = cleaner(1000, 200_000);
        metrics().registerRetentionLag("lag-test", cleaner::getLagSeconds);

        cleaner.clean();

        assertEquals(Duration.ofDays(3).toSeconds(),
                registry.get("yomi.httplog.retention.lag").tag("service", "lag-test").gauge().value(), 10);
    }

    private HttpLogRetentionCleaner cleaner(int chunkSize, int maxDeletePerRun) {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setServiceName("cleaner-test");
        properties.getRetention().setChunkSize(chunkSize);
        properties.getRetention().setMaxDeletePerRun(maxDeletePerRun);
        return new HttpLogRetentionCleaner(properties, new HttpLogKeys(properties, null), repository, metrics());
    }

    private HttpLogMetrics metrics() {
        return new HttpLogMetrics(new StaticListableBeanFactory(Map.of("meterRegistry", registry))
                .getBeanProvider(MeterRegistry.class));
    }

    /** retention.deleted 计数（未注册按 0 计） */
    private double deleted() {
        Counter counter = counter();
        return counter == null ? 0.0 : counter.count();
    }

    private Counter counter() {
        return registry.find("yomi.httplog.retention.deleted").counter();
    }

    /** 假仓储：按脚本返回删除行数，记录每块下传的 limit */
    private static final class FakeRepository implements IHttpLogRepository {

        final List<Integer> limits = new ArrayList<>();

        private final Deque<Integer> scriptedRows = new ArrayDeque<>();

        volatile Instant oldest;

        volatile RuntimeException failure;

        void scripted(Integer... rows) {
            scriptedRows.clear();
            scriptedRows.addAll(List.of(rows));
        }

        @Override
        public int deleteExpiredLimited(String serviceName, Instant createTimeBefore, int limit) {
            limits.add(limit);
            if (failure != null) {
                throw failure;
            }
            Integer rows = scriptedRows.poll();
            return Math.min(rows == null ? 0 : rows, limit);
        }

        @Override
        public Instant findOldestCreateTime(String serviceName) {
            return oldest;
        }

        @Override
        public Page<HttpLogEntity> pageSummary(HttpLogQuery condition, String serviceFilter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpLogEntity findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertBatch(List<HttpLogEntity> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(HttpLogEntity entity) {
            throw new UnsupportedOperationException();
        }

    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(HttpLogRetentionCleaner.class)).addAppender(appender);
        return appender;
    }

    private static void detachLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(HttpLogRetentionCleaner.class)).detachAppender(appender);
    }

}
