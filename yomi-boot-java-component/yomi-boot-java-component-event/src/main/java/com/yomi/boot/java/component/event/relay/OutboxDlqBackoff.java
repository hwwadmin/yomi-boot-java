package com.yomi.boot.java.component.event.relay;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DLQ 重试间隔：指数退避 + 抖动（对齐 Go {@code dlqRetryDelay})
 *
 * @author zwei
 */
final class OutboxDlqBackoff {

    /** DLQ 初始退避时长 */
    private static final Duration INITIAL = Duration.ofSeconds(1);
    /** DLQ 最大退避时长 */
    private static final Duration MAX = Duration.ofSeconds(60);

    private OutboxDlqBackoff() {
    }

    /** 第 {@code retryCount} 次失败后的等待时长 */
    static Duration delayAfterFailure(int retryCount) {
        if (retryCount <= 0) {
            return Duration.ZERO;
        }
        Duration base = baseDelay(retryCount);
        if (base.isZero()) {
            return Duration.ZERO;
        }
        long min = base.toMillis() * 8 / 10;
        long max = base.toMillis() * 12 / 10;
        if (max <= min) {
            return base;
        }
        long jitter = ThreadLocalRandom.current().nextLong(min, max + 1);
        return Duration.ofMillis(jitter);
    }

    /** 按重试次数计算 DLQ 退避时长 */
    private static Duration baseDelay(int retryCount) {
        int shift = retryCount - 1;
        if (shift > 6) {
            return MAX;
        }
        long millis = INITIAL.toMillis() * (1L << shift);
        if (millis > MAX.toMillis()) {
            return MAX;
        }
        return Duration.ofMillis(millis);
    }

}
