package com.yomi.boot.java.component.event.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.event.retention.EventRetentionCleaner;
import com.yomi.boot.java.component.redis.schedule.ClusterTask;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留期清理任务的装配与留痕：各侧开关（outbox/inbox-retention-enabled）独立生效，
 * 开关打开却不会装配对应清理任务时留 WARN
 *
 * @author zwei
 */
class EventRetentionTaskWiringTest {

    /** 消费侧只开收件箱清理开关即装配（与发件箱开关互不依赖） */
    @Test
    void inboxRetentionTaskWiredByInboxFlagAlone() {
        consumerRunner()
                .withPropertyValues("yomi.event.retention.inbox-retention-enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    Map<String, ClusterTask> tasks = context.getBeansOfType(ClusterTask.class);
                    assertEquals(1, tasks.size(), "消费侧只应装配收件箱清理任务: " + tasks.keySet());
                    assertEquals(EventRetentionCleaner.TASK_NAME_INBOX,
                            tasks.values().iterator().next().getTaskName());
                });
    }

    /** 未开 retention：不装配任何后台任务 */
    @Test
    void noTaskWhenRetentionDisabled() {
        consumerRunner().run(context -> {
            assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
            assertTrue(context.getBeansOfType(ClusterTask.class).isEmpty(),
                    "retention 关闭时不应装配清理任务: " + context.getBeansOfType(ClusterTask.class).keySet());
        });
    }

    /** 开了发件箱清理开关但未启用发布侧：清理任务不会装配，此时留 WARN */
    @Test
    void outboxFlagWithoutPublisherWarns() {
        EventProperties properties = new EventProperties();
        properties.setConsumerEnabled(true);
        properties.getRetention().setOutboxRetentionEnabled(true);

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            assertDoesNotThrow(() -> EventAutoConfiguration.warnIfRetentionRegistersNothing(properties));
        } finally {
            detachLogs(logs);
        }

        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("发件箱保留期清理不会装配")),
                "开了开关却没有清理任务必须留痕: " + messages(logs));
    }

    /** 开了收件箱清理开关但未启用消费侧：对称地留 WARN */
    @Test
    void inboxFlagWithoutConsumerWarns() {
        EventProperties properties = new EventProperties();
        properties.setPublisherEnabled(true);
        properties.getRetention().setInboxRetentionEnabled(true);

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            assertDoesNotThrow(() -> EventAutoConfiguration.warnIfRetentionRegistersNothing(properties));
        } finally {
            detachLogs(logs);
        }

        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("收件箱保留期清理不会装配")),
                "开了开关却没有清理任务必须留痕: " + messages(logs));
    }

    /** 开关关着时校验完全不动（不产生噪声） */
    @Test
    void validationSkipsWhenRetentionDisabled() {
        EventProperties properties = new EventProperties();
        properties.setConsumerEnabled(true);

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            assertDoesNotThrow(() -> EventAutoConfiguration.warnIfRetentionRegistersNothing(properties));
        } finally {
            detachLogs(logs);
        }

        assertTrue(logs.list.isEmpty(), "retention 关闭时不应有任何日志: " + messages(logs));
    }

    /**
     * 消费侧最小上下文：组件依赖全用桩（不连 DB/Redis/Kafka）；事务模板由 Boot 从唯一管理器自动注册
     * （组件不再自建模板）；清理任务的锁桩设为"始终繁忙"（不真正执行）
     */
    private static ApplicationContextRunner consumerRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class,
                        EventAutoConfiguration.class, LifecycleAutoConfiguration.class))
                .withPropertyValues("yomi.event.consumer-enabled=true",
                        "yomi.event.service-name=retention-wiring-test",
                        "spring.application.name=event-retention-wiring")
                .withBean(SpringUtils.class)
                .withBean("stringRedisTemplate", StringRedisTemplate.class, EventRetentionTaskWiringTest::stubRedisTemplate)
                .withBean("redissonClient", RedissonClient.class, EventRetentionTaskWiringTest::busyRedissonStub)
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> proxy(PlatformTransactionManager.class))
                .withBean("sqlSessionFactory", SqlSessionFactory.class, EventRetentionTaskWiringTest::stubSqlSessionFactory);
    }

    /** 让装配类扫出的 Mapper 能实例化：真实 Configuration + 空 DataSource（不触库） */
    private static SqlSessionFactory stubSqlSessionFactory() {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new org.apache.ibatis.mapping.Environment("retention-wiring-test",
                        new ManagedTransactionFactory(), proxy(DataSource.class)));
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** Redisson 桩：锁始终繁忙 → 任务本轮跳过（不触达真 Redis） */
    private static RedissonClient busyRedissonStub() {
        RLock lock = (RLock) Proxy.newProxyInstance(EventRetentionTaskWiringTest.class.getClassLoader(),
                new Class<?>[]{RLock.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "tryLock" -> false;
                    case "toString" -> "stub-lock";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
        return (RedissonClient) Proxy.newProxyInstance(EventRetentionTaskWiringTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLock" -> lock;
                    case "toString" -> "stub-redisson";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
    }

    /** StringRedisTemplate 桩：启动期只检查存在性 */
    private static StringRedisTemplate stubRedisTemplate() {
        RedisConnectionFactory connectionFactory = (RedisConnectionFactory) proxy(RedisConnectionFactory.class);
        return new StringRedisTemplate(connectionFactory);
    }

    /** 动态代理桩：未知方法返回默认值，仅 Object 方法给出合理实现 */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(EventRetentionTaskWiringTest.class.getClassLoader(),
                new Class<?>[]{type}, (p, method, args) -> switch (method.getName()) {
                    case "toString" -> "stub";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(EventAutoConfiguration.class)).addAppender(appender);
        return appender;
    }

    private static void detachLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(EventAutoConfiguration.class)).detachAppender(appender);
    }

    private static String messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
    }

}
