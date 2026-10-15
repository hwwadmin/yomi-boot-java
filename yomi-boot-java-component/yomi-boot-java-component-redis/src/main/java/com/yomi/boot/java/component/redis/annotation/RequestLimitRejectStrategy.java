package com.yomi.boot.java.component.redis.annotation;

/**
 * {@link RequestLimit} 在未能立即获得令牌时的处理策略。
 */
public enum RequestLimitRejectStrategy {

    /**
     * 直接拒绝，不占用业务线程等待。
     */
    REJECT,

    /**
     * 在 {@link RequestLimit#ttl()} 毫秒内轮询等待令牌，超时后拒绝。
     */
    WAIT_UNTIL_TIMEOUT

}
