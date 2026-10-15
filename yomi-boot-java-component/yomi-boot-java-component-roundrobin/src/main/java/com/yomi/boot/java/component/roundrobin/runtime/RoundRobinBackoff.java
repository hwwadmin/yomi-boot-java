package com.yomi.boot.java.component.roundrobin.runtime;

import java.time.Duration;

/**
 * 连续异常指数退避
 *
 * @author zwei
 */
public final class RoundRobinBackoff {

    /** 退避间隔上限 */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    /** 连续失败告警阈值 */
    private static final int MAX_CONSECUTIVE_WARN = 10;

    private RoundRobinBackoff() {
    }

    /** 连续失败后的退避时长 */
    public static Duration duration(int consecutiveErrors) {
        if (consecutiveErrors <= 0) {
            return Duration.ofSeconds(1);
        }
        int shift = Math.min(consecutiveErrors - 1, 6);
        long seconds = 1L << shift;
        Duration backoff = Duration.ofSeconds(seconds);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    /** 连续失败多少次后打告警 */
    public static int maxConsecutiveWarn() {
        return MAX_CONSECUTIVE_WARN;
    }

}
