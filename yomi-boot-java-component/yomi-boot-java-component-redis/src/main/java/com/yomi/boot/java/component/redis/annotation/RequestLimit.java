package com.yomi.boot.java.component.redis.annotation;

import java.lang.annotation.*;

/**
 * 请求限流注解
 *
 * @author zwei
 */
@SuppressWarnings("unused")
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestLimit {

    /**
     * key must not empty
     * 支持 spring spel, 当包含 # 符号，才解析 spel
     */
    String key();

    /**
     * 令牌补充频率，每秒多少个
     */
    int replenishRate() default 1;

    /**
     * 桶容量
     */
    int burstCapacity() default 1;

    /**
     * 请求令牌数量
     */
    int requestedTokens() default 1;

    /**
     * 等待时长（毫秒），仅 {@link RequestLimitRejectStrategy#WAIT_UNTIL_TIMEOUT} 时生效。
     */
    int ttl() default 1000;

    /**
     * 无可用令牌时的策略：直接拒绝，或等待至 {@link #ttl()} 超时。
     * <p>
     * 注意：{@link RequestLimitRejectStrategy#WAIT_UNTIL_TIMEOUT} 会阻塞当前线程直至获得令牌或超时，
     * 高并发下等待请求会占满线程池，且 Redis 故障时同样等待到超时；Web 场景优先使用 {@link RequestLimitRejectStrategy#REJECT}
     */
    RequestLimitRejectStrategy rejectStrategy() default RequestLimitRejectStrategy.REJECT;

}
