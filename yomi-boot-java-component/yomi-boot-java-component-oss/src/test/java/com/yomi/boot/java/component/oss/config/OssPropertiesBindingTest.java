package com.yomi.boot.java.component.oss.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * yml 键 → {@link OssProperties} 绑定验证（新增子段必须能从配置绑上）。
 */
class OssPropertiesBindingTest {

    /** 与 quick-starter dev yml 的 rustfs 示例一致 */
    @Test
    void bindsRustfsSectionFromConfig() {
        Map<String, Object> source = Map.of(
                "yomi.oss.enabled", "true",
                "yomi.oss.provider", "rustfs",
                "yomi.oss.public-base-url", "",
                "yomi.oss.max-presign-expiry", "30m",
                "yomi.oss.rustfs.endpoint", "http://127.0.0.1:9000",
                "yomi.oss.rustfs.access-key-id", "rustfsadmin",
                "yomi.oss.rustfs.secret-access-key", "rustfsadmin");

        OssProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("yomi.oss", Bindable.of(OssProperties.class))
                .orElseThrow(() -> new AssertionError("yomi.oss 绑定失败"));

        assertTrue(properties.isEnabled());
        assertEquals(OssProvider.RUSTFS, properties.resolvedProvider());
        assertEquals("http://127.0.0.1:9000", properties.getRustfs().getEndpoint());
        assertEquals("rustfsadmin", properties.getRustfs().getAccessKeyId());
        assertNull(properties.getRustfs().getRegion());
        assertDoesNotThrow(properties::validate);
    }

    /** 未配置的子段保持默认实例，且不参与校验 */
    @Test
    void absentSectionsKeepDefaults() {
        OssProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of("yomi.oss.enabled", "false")))
                .bind("yomi.oss", Bindable.of(OssProperties.class))
                .orElseThrow(() -> new AssertionError("yomi.oss 绑定失败"));

        assertFalse(properties.isEnabled());
        assertNotNull(properties.getRustfs());
        assertDoesNotThrow(properties::validate);
    }

}
