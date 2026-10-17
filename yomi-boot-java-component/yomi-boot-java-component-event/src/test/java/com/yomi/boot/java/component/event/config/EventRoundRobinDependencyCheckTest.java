package com.yomi.boot.java.component.event.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.event.support.EventKeys;
import com.yomi.boot.java.component.roundrobin.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台任务对 RoundRobin 的依赖校验：开关开启而 RoundRobin 不可用时启动失败，
 * 消费侧"仅消费 + retention 开启 + roundrobin 关闭"不会注册任何清理任务。
 */
class EventRoundRobinDependencyCheckTest {

    /** 消费侧开了收件箱清理但 RoundRobin 不可用：启动失败并给出配置键 */
    @Test
    void inboxRetentionWithoutRoundRobinFailsFast() {
        EventProperties properties = consumerOnly();
        properties.getRetention().setEnabled(true);
        properties.getRetention().setInboxRetentionEnabled(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> EventAutoConfiguration.requireRoundRobinForRetention(properties, false));

        assertTrue(ex.getMessage().contains("yomi.event.retention.inbox-retention-enabled=true"), ex.getMessage());
        assertTrue(ex.getMessage().contains("yomi.roundrobin.enabled=true"), ex.getMessage());
    }

    /** 仅消费侧 + retention 开启但没开收件箱清理：不会有任何清理任务，此时记录 WARN */
    @Test
    void consumerOnlyRetentionWithoutInboxFlagWarns() {
        EventProperties properties = consumerOnly();
        properties.getRetention().setEnabled(true);

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            assertDoesNotThrow(() -> EventAutoConfiguration.requireRoundRobinForRetention(properties, false));
        } finally {
            detachLogs(logs);
        }

        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("不会注册任何清理任务")),
                "开了 retention 却没有任何清理任务必须留痕: " + messages(logs));
    }

    /** RoundRobin 就位时校验放行 */
    @Test
    void retentionPassesWhenRoundRobinReady() {
        EventProperties properties = consumerOnly();
        properties.getRetention().setEnabled(true);
        properties.getRetention().setInboxRetentionEnabled(true);

        assertDoesNotThrow(() -> EventAutoConfiguration.requireRoundRobinForRetention(properties, true));
    }

    /** 未开 retention 时校验完全不动（不产生噪声） */
    @Test
    void validationSkipsWhenRetentionDisabled() {
        EventProperties properties = consumerOnly();

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            assertDoesNotThrow(() -> EventAutoConfiguration.requireRoundRobinForRetention(properties, false));
        } finally {
            detachLogs(logs);
        }

        assertTrue(logs.list.isEmpty(), "retention 关闭时不应有任何日志: " + messages(logs));
    }

    /** 中继/清理的依赖：以 RoundRobinRegistry 是否注册为判据，属性值不参与判断（'1'/'yes' 不代表已注册） */
    @Test
    void relayDependencyNamesRegistryCriterion() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> EventAutoConfiguration.requireRoundRobin(false, "yomi.event.publisher-enabled=true"));

        assertTrue(ex.getMessage().contains("yomi.event.publisher-enabled=true"), ex.getMessage());
        assertTrue(ex.getMessage().contains("yomi.roundrobin.enabled=true"), ex.getMessage());
        assertTrue(ex.getMessage().contains("RoundRobinRegistry"), "要点名真实判据: " + ex.getMessage());

        assertDoesNotThrow(() -> EventAutoConfiguration.requireRoundRobin(true, "yomi.event.publisher-enabled=true"));
    }

    /** Bean 条件与校验口径一致：收件箱清理 Bean 受 yomi.roundrobin.enabled 控制 */
    @Test
    void inboxRetentionCleanerBeanIsGatedByRoundRobin() throws Exception {
        Method method = EventAutoConfiguration.ConsumerConfiguration.class.getMethod("eventInboxRetentionCleaner",
                EventProperties.class, com.yomi.boot.java.component.event.support.EventKeys.class,
                com.yomi.boot.java.component.event.repository.IEventInboxRepository.class,
                com.yomi.boot.java.component.roundrobin.RoundRobinRegistry.class);

        Set<String> keys = Arrays.stream(method.getAnnotationsByType(ConditionalOnProperty.class))
                .flatMap(annotation -> annotation.name().length > 0
                        ? Arrays.stream(annotation.name()) : Arrays.stream(annotation.value()))
                .collect(Collectors.toSet());

        assertTrue(keys.contains("yomi.roundrobin.enabled"),
                "清理任务由轮询调度，Bean 条件必须与启动校验同一口径: " + keys);
    }

    /** 校验真的挂在启动路径上（只消费侧 + 收件箱清理 + 无 RoundRobinRegistry → 启动失败） */
    @Test
    void bootstrapFailsFastForConsumerOnlyRetentionWithoutRoundRobin() {
        EventProperties properties = consumerOnly();
        properties.getRetention().setEnabled(true);
        properties.getRetention().setInboxRetentionEnabled(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> bootstrap(properties, null));

        assertTrue(ex.getMessage().contains("inbox-retention-enabled"), ex.getMessage());
    }

    /** RoundRobin 就位时启动校验放行（消费侧依赖齐备） */
    @Test
    void bootstrapPassesWithRoundRobinRegistry() {
        EventProperties properties = consumerOnly();
        properties.getRetention().setEnabled(true);
        properties.getRetention().setInboxRetentionEnabled(true);

        assertDoesNotThrow(() -> bootstrap(properties, registryForRegistration()));
    }

    /** 直接调用装配类的 @Bean 方法（发布侧关闭，故 ApplicationContext 不参与） */
    private static void bootstrap(EventProperties properties, RoundRobinRegistry registry) {
        new EventAutoConfiguration().eventBootstrap(properties, new EventKeys("test"), null,
                provider(null), provider(inboxMapperStub()), provider(new StringRedisTemplate()),
                provider(redissonStub()), provider(null), provider(registry));
    }

    /** 只注册不启动的注册表（锁客户端不会被动用） */
    private static RoundRobinRegistry registryForRegistration() {
        return new RoundRobinRegistry(new RoundRobinLockClient(null, null, null));
    }

    private static com.yomi.boot.java.component.event.repository.mapper.EventInboxMapper inboxMapperStub() {
        return (com.yomi.boot.java.component.event.repository.mapper.EventInboxMapper) Proxy.newProxyInstance(
                EventRoundRobinDependencyCheckTest.class.getClassLoader(),
                new Class<?>[]{com.yomi.boot.java.component.event.repository.mapper.EventInboxMapper.class},
                (proxy, method, args) -> null);
    }

    private static RedissonClient redissonStub() {
        return (RedissonClient) Proxy.newProxyInstance(EventRoundRobinDependencyCheckTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> null);
    }

    /** 只回答 getIfAvailable 的 ObjectProvider 桩（null 表示 Bean 不存在） */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public java.util.stream.Stream<T> stream() {
                return value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value);
            }

            @Override
            public void forEach(java.util.function.Consumer<? super T> action) {
                if (value != null) {
                    action.accept(value);
                }
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return value == null ? java.util.Collections.emptyIterator()
                        : java.util.Collections.singletonList(value).iterator();
            }
        };
    }

    private static EventProperties consumerOnly() {
        EventProperties properties = new EventProperties();
        properties.setConsumerEnabled(true);
        return properties;
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
