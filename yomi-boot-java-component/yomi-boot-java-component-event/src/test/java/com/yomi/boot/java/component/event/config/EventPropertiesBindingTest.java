package com.yomi.boot.java.component.event.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code yomi.event.retention.*} 绑定验证：取值与 quick-starter 两份 yml 一致，
 * 校验 yomi.event 配置可正确绑定（含 Duration 简写 14d/1h）
 */
class EventPropertiesBindingTest {

    @Test
    void bindsRetentionPropertiesFromQuickStarterConfig() {
        Map<String, Object> source = Map.of(
                "yomi.event.retention.outbox-retention-enabled", "true",
                "yomi.event.retention.outbox-retention", "14d",
                "yomi.event.retention.inbox-retention-enabled", "false",
                "yomi.event.retention.inbox-retention", "14d",
                "yomi.event.retention.scan-interval", "1h",
                "yomi.event.retention.batch-size", "1000",
                "yomi.event.retention.max-delete-per-run", "50000");

        EventProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("yomi.event", Bindable.of(EventProperties.class))
                .orElseThrow(() -> new AssertionError("yomi.event 绑定失败"));

        EventProperties.Retention retention = properties.getRetention();
        assertNotNull(retention);
        assertTrue(retention.isOutboxRetentionEnabled());
        assertEquals(Duration.ofDays(14), retention.getOutboxRetention());
        assertFalse(retention.isInboxRetentionEnabled());
        assertEquals(Duration.ofDays(14), retention.getInboxRetention());
        assertEquals(Duration.ofHours(1), retention.getScanInterval());
        assertEquals(1000, retention.getBatchSize());
        assertEquals(50_000, retention.getMaxDeletePerRun());
    }

    @Test
    void retentionKeepsDefaultsWhenKeyAbsent() {
        // 只配其它 event 键（未出现 retention.*）：清理必须保持默认关闭、保留期取默认值
        EventProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of("yomi.event.fetch-batch-size", "50")))
                .bind("yomi.event", Bindable.of(EventProperties.class))
                .orElseThrow(() -> new AssertionError("yomi.event 绑定失败"));

        assertFalse(properties.getRetention().isOutboxRetentionEnabled(), "缺省必须关闭清理（表保持只增不删）");
        assertFalse(properties.getRetention().isInboxRetentionEnabled());
        assertEquals(Duration.ofDays(14), properties.getRetention().getOutboxRetention());
        assertEquals(Duration.ofHours(1), properties.getRetention().getScanInterval());
    }

}
