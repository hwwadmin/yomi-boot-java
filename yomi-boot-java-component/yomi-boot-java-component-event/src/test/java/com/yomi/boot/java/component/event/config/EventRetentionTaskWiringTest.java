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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
 * 保留期清理任务的装配与留痕：开关齐备才装配清理任务（集群定时任务），
 * retention 打开却不会装配任何清理任务时留 WARN
 *
 * @author zwei
 */
class EventRetentionTaskWiringTest {

    /** 消费侧 + retention + 收件箱清理齐备：装配一个收件箱清理任务 */
    @Test
    void inboxRetentionTaskWiredWhenBothFlagsOn() {
        consumerRunner()
                .withPropertyValues("yomi.event.retention.enabled=true",
                        "yomi.event.retention.inbox-retention-enabled=true")
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

    /** 仅消费侧 + retention 开启 + 未开收件箱清理：不会有任何清理任务，此时留 WARN */
    @Test
    void consumerOnlyRetentionWithoutInboxFlagWarns() {
        EventProperties properties = new EventProperties();
        properties.setConsumerEnabled(true);
        properties.getRetention().setEnabled(true);

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            assertDoesNotThrow(() -> EventAutoConfiguration.warnIfRetentionRegistersNothing(properties));
        } finally {
            detachLogs(logs);
        }

        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("不会装配任何清理任务")),
                "开了 retention 却没有任何清理任务必须留痕: " + messages(logs));
    }

    /** retention 关闭时校验完全不动（不产生噪声） */
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

    /** 消费侧最小上下文：组件依赖全用桩（不连 DB/Redis/Kafka），清理任务的锁桩设为"始终繁忙"（不真正执行） */
    private static ApplicationContextRunner consumerRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EventAutoConfiguration.class))
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
