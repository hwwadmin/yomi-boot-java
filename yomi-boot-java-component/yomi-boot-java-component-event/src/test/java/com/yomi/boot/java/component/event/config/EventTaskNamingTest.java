package com.yomi.boot.java.component.event.config;

import com.yomi.boot.java.component.event.relay.OutboxRelayCoordinator;
import com.yomi.boot.java.component.event.retention.EventRetentionCleaner;
import com.yomi.boot.java.component.event.support.EventKeys;
import com.yomi.boot.java.component.redis.schedule.ClusterTask;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 事件后台任务的命名、键名与节奏：任务名直接取任务类上的常量（不带服务名后缀与分隔符），
 * 键名 = {@code yomi:schedule:{spring.application.name}:{turn|lock}:{任务名}}，间隔取各自配置键。
 * <p>直接调用装配类的 @Bean 方法并注入容器的 Environment（任务不被容器启动，故不触达 Redis）
 *
 * @author zwei
 */
class EventTaskNamingTest {

    @Test
    void taskNamesAreBareConstantsAndIntervalsComeFromProperties() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.application.name=event-task-naming")
                .run(context -> {
                    Environment environment = context.getEnvironment();
                    LifecycleProperties lifecycleProperties = new LifecycleProperties();
                    EventProperties properties = new EventProperties();
                    properties.setFetchInterval(Duration.ofMillis(500));
                    properties.setDlqScanInterval(Duration.ofMillis(700));
                    properties.getRetention().setScanInterval(Duration.ofMinutes(30));
                    EventKeys keys = new EventKeys("demo");
                    OutboxRelayCoordinator coordinator =
                            new OutboxRelayCoordinator(properties, keys, null, null, null);
                    EventAutoConfiguration.PublisherConfiguration publisher =
                            new EventAutoConfiguration.PublisherConfiguration();
                    EventAutoConfiguration.ConsumerConfiguration consumer =
                            new EventAutoConfiguration.ConsumerConfiguration();

                    Map<String, ClusterTask> tasks = Map.of(
                            OutboxRelayCoordinator.TASK_FETCH,
                            publisher.eventOutboxFetchTask(properties, coordinator, environment,
                                    lifecycleProperties),
                            OutboxRelayCoordinator.TASK_FETCH_TIMEOUT,
                            publisher.eventOutboxFetchTimeoutTask(properties, coordinator, environment,
                                    lifecycleProperties),
                            OutboxRelayCoordinator.TASK_DLQ,
                            publisher.eventDlqRetryTask(properties, coordinator, environment,
                                    lifecycleProperties),
                            EventRetentionCleaner.TASK_NAME_OUTBOX,
                            publisher.eventOutboxRetentionTask(properties,
                                    publisher.eventOutboxRetentionCleaner(properties, keys, null), environment,
                                    lifecycleProperties),
                            EventRetentionCleaner.TASK_NAME_INBOX,
                            consumer.eventInboxRetentionTask(properties,
                                    consumer.eventInboxRetentionCleaner(properties, keys, null), environment,
                                    lifecycleProperties));

                    tasks.forEach((name, task) -> {
                        assertEquals(name, task.getTaskName(), "任务名必须是任务类上的常量本身");
                        assertFalse(name.contains(":") || name.contains("_") || name.contains("-"),
                                name + " 不应含分隔符或服务名后缀");
                        assertEquals("yomi:schedule:event-task-naming:turn:" + name, task.getTurnKey());
                        assertEquals("yomi:schedule:event-task-naming:lock:" + name, task.getLockKey());
                        assertEquals(Duration.ofSeconds(24), task.getShutdownAwait(),
                                "未配阶段超时 → Boot 默认 30s 取 80%");
                    });
                    assertEquals(Duration.ofMillis(500), tasks.get(OutboxRelayCoordinator.TASK_FETCH).getInterval());
                    assertEquals(Duration.ofMillis(500),
                            tasks.get(OutboxRelayCoordinator.TASK_FETCH_TIMEOUT).getInterval(),
                            "FETCHED 超时回收与抓取共用 yomi.event.fetch-interval");
                    assertEquals(Duration.ofMillis(700), tasks.get(OutboxRelayCoordinator.TASK_DLQ).getInterval());
                    assertEquals(Duration.ofMinutes(30),
                            tasks.get(EventRetentionCleaner.TASK_NAME_OUTBOX).getInterval());
                    assertEquals(Duration.ofMinutes(30),
                            tasks.get(EventRetentionCleaner.TASK_NAME_INBOX).getInterval());
                });
    }

}
