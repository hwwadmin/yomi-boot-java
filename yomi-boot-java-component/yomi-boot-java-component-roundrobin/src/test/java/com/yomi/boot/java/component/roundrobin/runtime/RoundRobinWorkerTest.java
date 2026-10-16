package com.yomi.boot.java.component.roundrobin.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停机等待：协作式停机下"没等到"必须留痕（WARN），正常结束则保持安静
 */
class RoundRobinWorkerTest {

    @Test
    void awaitStopWarnsWhenThreadOutlivesShutdownAwait() throws Exception {
        Thread thread = Thread.ofVirtual().name("roundrobin-stuck-task").unstarted(() -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        RoundRobinWorker worker = new RoundRobinWorker(thread, Duration.ofMillis(50));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinWorker.class);
        logger.addAppender(appender);
        boolean aliveAfterAwait;
        try {
            worker.awaitStop(Duration.ofMillis(50));
            aliveAfterAwait = thread.isAlive();
        } finally {
            logger.detachAppender(appender);
            thread.interrupt();
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertTrue(aliveAfterAwait, "前提：线程确实没在 shutdown-await 内结束");
        assertTrue(logs.contains("未结束"), "超时必须打 WARN，不能静默: " + logs);
        assertTrue(logs.contains("roundrobin-stuck-task"), "日志要点名是哪个任务: " + logs);
        assertTrue(logs.contains("ctx.isCancelled") || logs.contains("shutdown-await"), "日志要给出出路: " + logs);
    }

    @Test
    void awaitStopStaysQuietWhenThreadStopsInTime() throws Exception {
        Thread thread = Thread.ofVirtual().name("roundrobin-fast-task").unstarted(() -> {
        });
        thread.start();
        RoundRobinWorker worker = new RoundRobinWorker(thread, Duration.ofSeconds(5));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinWorker.class);
        logger.addAppender(appender);
        try {
            worker.awaitStop(Duration.ofSeconds(5));
        } finally {
            logger.detachAppender(appender);
        }

        assertFalse(thread.isAlive(), "线程应已结束");
        assertTrue(appender.list.isEmpty(), "正常停机不得打 WARN: " + appender.list);
    }

}
