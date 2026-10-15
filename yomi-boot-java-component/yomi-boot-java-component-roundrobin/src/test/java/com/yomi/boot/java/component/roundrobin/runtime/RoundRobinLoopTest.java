package com.yomi.boot.java.component.roundrobin.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient.UnlockOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务异常触发连续失败计数与指数退避，避免高频重试
 */
class RoundRobinLoopTest {

    private ScheduledExecutorService scheduler;

    @AfterEach
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void taskFailuresAreCountedAndBackedOff() throws Exception {
        scheduler = Executors.newScheduledThreadPool(1);
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();
        RoundRobinLoop loop = new RoundRobinLoop(new RoundRobinTick(new HeldLockClient(), scheduler));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinLoop.class);
        logger.addAppender(appender);
        try {
            Thread worker = Thread.ofVirtual().start(() -> loop.run(token, config(), ctx -> {
                if (runs.incrementAndGet() >= 2) {
                    token.cancel();
                }
                throw new IllegalStateException("下游故障");
            }));
            worker.join(10_000);
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(event -> {
            String message = event.getFormattedMessage();
            if (event.getThrowableProxy() != null) {
                message += " | " + event.getThrowableProxy().getClassName()
                        + ": " + event.getThrowableProxy().getMessage();
            }
            return message;
        }).collect(Collectors.joining("\n"));
        assertTrue(runs.get() >= 2, "任务应被重复执行（失败后仍要按退避节奏重试）");
        assertTrue(logs.contains("consecutiveErrors=1"), "第一次失败必须计入连续失败并退避: " + logs);
        assertTrue(logs.contains("退避"), "日志应给出本次退避时长: " + logs);
        assertTrue(logs.contains("下游故障"), "日志应带上原始异常: " + logs);
    }

    /** 构造 RoundRobin 测试配置 */
    private static RoundRobinConfig config() {
        return RoundRobinConfig.builder()
                .name("failing-task")
                .frequency(Duration.ofMillis(50))
                .randomDelayMin(Duration.ofMillis(50))
                .randomDelayMax(Duration.ofMillis(60))
                .wakeUpInterval(Duration.ofMillis(50))
                .execLockTtl(Duration.ofSeconds(30))
                .execWatchdog(true)
                .lockWait(Duration.ZERO)
                .shutdownAwait(Duration.ofSeconds(30))
                .build();
    }

    /** 锁客户端桩：锁一直视为持有，不触达 Redis */
    static class HeldLockClient extends RoundRobinLockClient {

        HeldLockClient() {
            super(null, null, null);
        }

        @Override
        public boolean tryAcquireFrequency(RoundRobinConfig config) {
            return true;
        }

        @Override
        public RLock tryAcquireExecution(RoundRobinConfig config) {
            return (RLock) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RLock.class},
                    (proxy, method, args) -> null);
        }

        @Override
        public boolean isHeldByWorkerThread(RLock lock, long workerThreadId) {
            return true;
        }

        @Override
        public UnlockOutcome unlockQuietly(RoundRobinConfig config, RLock lock) {
            // 测试桩：不触达 Redis
            return UnlockOutcome.RELEASED;
        }
    }

}
