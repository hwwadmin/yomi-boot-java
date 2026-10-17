package com.yomi.boot.java.component.event.config;

import com.yomi.boot.java.component.event.relay.OutboxRelayCoordinator;
import com.yomi.boot.java.component.event.retention.EventRetentionCleaner;
import com.yomi.boot.java.component.event.support.EventKeys;
import com.yomi.boot.java.component.roundrobin.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件后台任务注册名约定：直接使用任务类上的常量，不带服务名后缀
 * （应用隔离由 roundrobin 前缀保证：{@code yomi.roundrobin.store-prefix}）
 *
 * @author zwei
 */
class EventTaskNamingTest {

    @Test
    void registeredTaskNames_areBareConstants() {
        EventProperties properties = new EventProperties();
        EventKeys keys = new EventKeys("demo");
        // 只注册不启动：锁客户端不会被动用
        RoundRobinRegistry registry = new RoundRobinRegistry(new RoundRobinLockClient(null, null, null));
        OutboxRelayCoordinator coordinator = new OutboxRelayCoordinator(properties, keys, null, null, null);
        EventAutoConfiguration.PublisherConfiguration configuration =
                new EventAutoConfiguration.PublisherConfiguration();
        configuration.eventOutboxRoundRobinRegistrar(properties, keys, coordinator, registry);
        configuration.eventOutboxRetentionCleaner(properties, keys, null, registry);

        for (String name : List.of(
                OutboxRelayCoordinator.TASK_FETCH,
                OutboxRelayCoordinator.TASK_FETCH_TIMEOUT,
                OutboxRelayCoordinator.TASK_DLQ,
                EventRetentionCleaner.TASK_NAME_OUTBOX)) {
            assertTrue(registry.contains(name), name + " 应为注册名本身（不带服务名后缀）");
            assertTrue(name.indexOf('_') < 0 && name.indexOf(':') < 0, name + " 不应含分隔符");
        }
        assertFalse(registry.contains(OutboxRelayCoordinator.TASK_FETCH + "_demo"));
    }

}
