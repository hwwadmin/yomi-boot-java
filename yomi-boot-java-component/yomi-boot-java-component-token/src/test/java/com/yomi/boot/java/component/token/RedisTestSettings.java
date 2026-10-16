package com.yomi.boot.java.component.token;

import org.apache.commons.lang3.StringUtils;

/**
 * Redis 集成测试连接参数（测试辅助）
 * <p>
 * 优先读系统属性 {@code yomi.test.redis.*}，其次读环境变量 {@code YOMI_REDIS_*}；
 * 全部未配置时集成用例整体跳过。
 *
 * @author zwei
 */
public final class RedisTestSettings {

    private RedisTestSettings() {
    }

    public static boolean configured() {
        return StringUtils.isNotBlank(host());
    }

    public static String host() {
        return setting("yomi.test.redis.host", "YOMI_REDIS_HOST");
    }

    public static int port() {
        return intSetting("yomi.test.redis.port", "YOMI_REDIS_PORT", 6379);
    }

    public static String password() {
        return setting("yomi.test.redis.password", "YOMI_REDIS_PASSWORD");
    }

    public static int database() {
        return intSetting("yomi.test.redis.database", "YOMI_REDIS_DATABASE", 0);
    }

    /** 读取 Redis 集成测试配置项 */
    private static String setting(String propertyKey, String envKey) {
        String value = System.getProperty(propertyKey);
        return StringUtils.isNotBlank(value) ? value : System.getenv(envKey);
    }

    /** 读取 Redis 集成测试整型配置 */
    private static int intSetting(String propertyKey, String envKey, int defaultValue) {
        String value = setting(propertyKey, envKey);
        return StringUtils.isBlank(value) ? defaultValue : Integer.parseInt(value.trim());
    }

}
