package com.yomi.boot.java.component.roundrobin.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoundRobinConfigTest {

    @Test
    void simpleValidates() {
        RoundRobinDefaults defaults = RoundRobinDefaults.builder().build();
        RoundRobinConfig cfg = RoundRobinConfig.simple("demo", Duration.ofMillis(200), defaults);
        assertDoesNotThrow(cfg::validate);
    }

    @Test
    void maxHoldLessThanExecLockFails() {
        RoundRobinConfig cfg = RoundRobinConfig.builder()
                .name("x")
                .frequency(Duration.ofSeconds(1))
                .randomDelayMin(Duration.ofMillis(100))
                .randomDelayMax(Duration.ofMillis(200))
                .wakeUpInterval(Duration.ofMillis(100))
                .execLockTtl(Duration.ofSeconds(30))
                .execWatchdog(true)
                .maxHoldDuration(Duration.ofSeconds(5))
                .lockWait(Duration.ZERO)
                .shutdownAwait(Duration.ofSeconds(30))
                .build();
        assertThrows(IllegalStateException.class, cfg::validate);
    }

    /** 任务级参数不在 yml 里：报错指向 @RoundRobinTask 属性并带任务名，yml 配置键不出现在报错里 */
    @Test
    void invalidParameterMessagePointsToAnnotationAttribute() {
        RoundRobinDefaults defaults = RoundRobinDefaults.builder().build();
        RoundRobinConfig cfg = RoundRobinConfig.simple("demo-task", Duration.ofMillis(10), defaults);

        IllegalStateException e = assertThrows(IllegalStateException.class, cfg::validate);

        String message = e.getMessage();
        assertTrue(message.contains("frequency"), message);
        assertTrue(message.contains("demo-task"), "报错应带上任务名: " + message);
        assertTrue(message.contains("@RoundRobinTask"), "报错应指向注解属性: " + message);
        assertFalse(message.contains("yomi.roundrobin."), "不应指向不存在的 yml 配置键: " + message);
    }

    /** watchdog=false 是硬租约：maxHold 大于租约意味着锁先过期、其它实例可能并发执行，启动期即报错 */
    @Test
    void watchdogOffRejectsMaxHoldBeyondLease() {
        RoundRobinConfig cfg = config(false, Duration.ofSeconds(30), Duration.ofMinutes(1));

        IllegalStateException e = assertThrows(IllegalStateException.class, cfg::validate);

        String message = e.getMessage();
        assertTrue(message.contains("maxHoldDuration 不能大于 execLockTtl"), message);
        assertTrue(message.contains("并发执行"), "报错应点明后果: " + message);
    }

    @Test
    void watchdogOffAllowsMaxHoldWithinLease() {
        assertDoesNotThrow(() -> config(false, Duration.ofSeconds(30), Duration.ofSeconds(10)).validate());
        assertDoesNotThrow(() -> config(false, Duration.ofSeconds(30), Duration.ofSeconds(30)).validate(),
                "maxHold 等于租约边界值允许（软取消与硬过期同时触发）");
    }

    /** watchdog=true 时 execLockTtl 不参与锁租约：显式配置会打 WARN，提醒该值不生效 */
    @Test
    void watchdogOnWarnsWhenExecLockTtlExplicitlySet() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(RoundRobinConfig.class);
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> config(true, Duration.ofMinutes(5), null).validate());
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logs.contains("execLockTtl=PT5M"), "显式配置的租约值应被点名: " + logs);
            assertTrue(logs.contains("不参与锁租约"), logs);

            appender.list.clear();
            assertDoesNotThrow(() -> config(true, RoundRobinDefaults.DEFAULT_EXEC_LOCK_TTL, null).validate());
            assertTrue(appender.list.isEmpty(), "默认值不得告警（不能误伤默认配置）");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** 构造 RoundRobin 测试配置 */
    private static RoundRobinConfig config(boolean watchdog, Duration ttl, Duration maxHold) {
        return RoundRobinConfig.builder()
                .name("x")
                .frequency(Duration.ofSeconds(1))
                .randomDelayMin(Duration.ofMillis(100))
                .randomDelayMax(Duration.ofMillis(200))
                .wakeUpInterval(Duration.ofMillis(100))
                .execLockTtl(ttl)
                .execWatchdog(watchdog)
                .maxHoldDuration(maxHold)
                .lockWait(Duration.ZERO)
                .shutdownAwait(Duration.ofSeconds(30))
                .build();
    }

}
