package com.yomi.boot.java.component.oss.impl;

import org.apache.commons.lang3.StringUtils;

/**
 * RustFS 集成测试连接参数（测试辅助）。
 * <p>
 * 优先读系统属性 {@code yomi.test.rustfs.*}，其次读环境变量 {@code YOMI_TEST_RUSTFS_*}；
 * endpoint 未配置时集成用例整体跳过。本地起 RustFS（官方 Docker 镜像）：
 * {@code docker run -d -p 9000:9000 -p 9001:9001 rustfs/rustfs:1.0.1}。
 *
 * @author zwei
 */
final class RustfsTestSettings {

    /** 官方镜像默认访问密钥 ID */
    private static final String DEFAULT_ACCESS_KEY_ID = "rustfsadmin";

    /** 官方镜像默认访问密钥 */
    private static final String DEFAULT_SECRET_ACCESS_KEY = "rustfsadmin";

    private RustfsTestSettings() {
    }

    static boolean configured() {
        return StringUtils.isNotBlank(endpoint());
    }

    static String endpoint() {
        return setting("yomi.test.rustfs.endpoint", "YOMI_TEST_RUSTFS_ENDPOINT");
    }

    static String accessKeyId() {
        return StringUtils.defaultIfBlank(
                setting("yomi.test.rustfs.access-key-id", "YOMI_TEST_RUSTFS_ACCESS_KEY_ID"), DEFAULT_ACCESS_KEY_ID);
    }

    static String secretAccessKey() {
        return StringUtils.defaultIfBlank(
                setting("yomi.test.rustfs.secret-access-key", "YOMI_TEST_RUSTFS_SECRET_ACCESS_KEY"),
                DEFAULT_SECRET_ACCESS_KEY);
    }

    /** 读取 RustFS 集成测试配置项 */
    private static String setting(String propertyKey, String envKey) {
        String value = System.getProperty(propertyKey);
        return StringUtils.isNotBlank(value) ? value : System.getenv(envKey);
    }

}
