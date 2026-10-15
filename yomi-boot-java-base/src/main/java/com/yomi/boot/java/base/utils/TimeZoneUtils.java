package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.UtilsException;
import org.apache.commons.lang3.StringUtils;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.TimeZone;

/**
 * 进程时区：供 {@link JsonUtils}、框架启动引导（无参 {@code AppBoot.run}）等统一读取。
 * <p>
 * 解析在类加载时完成且进程内不变。未配置时固定为 {@link #DEFAULT_ZONE_ID}（不跟随操作系统或 {@code TZ}）。
 * <p>
 * 配置来源（高 → 低）：
 * <ol>
 *     <li>系统属性 {@value #PROP_TIME_ZONE}（如 {@code -Dyomi.time-zone=Asia/Shanghai}）</li>
 *     <li>环境变量 {@value #ENV_TIME_ZONE}</li>
 * </ol>
 * 通过 {@code AppBoot.run(TimeZone, ...)} 显式传入的时区优先于本工具解析结果。
 * <p>
 * 配置非法时在类加载期抛 {@link UtilsException}，消息含配置键与取值。
 *
 * @author zwei
 */
public abstract class TimeZoneUtils {

    /** 默认时区 ID */
    public static final String DEFAULT_ZONE_ID = "UTC";

    /** 系统属性键，与 yml 中 {@code yomi.time-zone} 命名一致（本工具不读 yml） */
    public static final String PROP_TIME_ZONE = "yomi.time-zone";

    /** 环境变量键 */
    public static final String ENV_TIME_ZONE = "YOMI_TIME_ZONE";

    /** 类加载时解析并缓存的进程时区 */
    private static final ZoneId ZONE_ID = resolveZoneId(
            System.getProperty(PROP_TIME_ZONE), System.getenv(ENV_TIME_ZONE));

    private TimeZoneUtils() {
    }

    /**
     * 当前配置的时区
     */
    public static ZoneId zoneId() {
        return ZONE_ID;
    }

    /**
     * 当前配置的时区（{@link java.util.TimeZone}）
     */
    public static TimeZone timeZone() {
        return TimeZone.getTimeZone(ZONE_ID);
    }

    /**
     * 按优先级合并系统属性与环境变量，解析为 {@link ZoneId}；取值非法（格式错误或未知 ID）抛
     * {@link UtilsException}，消息含实际生效的配置键与取值
     */
    static ZoneId resolveZoneId(String propertyValue, String envValue) {
        String key = PROP_TIME_ZONE;
        String value = propertyValue;
        if (StringUtils.isBlank(value)) {
            key = ENV_TIME_ZONE;
            value = envValue;
        }
        if (StringUtils.isBlank(value)) {
            return ZoneId.of(DEFAULT_ZONE_ID);
        }
        String zoneId = value.trim();
        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            throw UtilsException.exception(e, "illegal time zone config: {}={}", key, zoneId);
        }
    }

}
