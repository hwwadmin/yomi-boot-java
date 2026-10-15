package com.yomi.boot.java.component.captcha.store;

import org.apache.commons.lang3.StringUtils;

/**
 * 存储实现选择
 *
 * @author zwei
 */
public enum CaptchaStoreType {

    /** 配了可用 Redis 用 redis，否则用 local（单实例可用） */
    AUTO,

    /** 本地内存（单实例；多实例部署会出现 A 实例签发、B 实例校验失败） */
    LOCAL,

    /** Redis（多实例；未配置 spring.data.redis.* 连接目标时启动失败） */
    REDIS,
    ;

    /**
     * 宽松解析（yml 通常写 local/redis/auto，大小写不限）
     * <p>
     * 装配条件里必须走这里：{@code Environment.getProperty(key, Class)} 的枚举转换是大小写敏感的，
     * 而 {@code @ConfigurationProperties} 绑定是宽松的，两条路径若不统一会在启动装配期直接报错。
     * 未知值按兜底处理，由属性绑定期给出明确的取值报错。
     */
    public static CaptchaStoreType parse(String value, CaptchaStoreType fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        for (CaptchaStoreType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return fallback;
    }

}
