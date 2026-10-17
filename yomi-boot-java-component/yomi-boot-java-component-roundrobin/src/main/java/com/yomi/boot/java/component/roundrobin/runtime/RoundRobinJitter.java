package com.yomi.boot.java.component.roundrobin.runtime;

import com.yomi.boot.java.component.roundrobin.RoundRobinTask;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 成功执行后的随机休眠
 *
 * @author zwei
 */
public final class RoundRobinJitter {

    private RoundRobinJitter() {
    }

    /** 在退避基础上加减随机抖动 */
    public static Duration duration(RoundRobinTask task) {
        Duration low = task.getInterval().plus(task.getJitterMin());
        Duration high = task.getInterval().plus(task.getJitterMax());
        if (!high.isPositive() || high.compareTo(low) <= 0) {
            return low;
        }
        long lowNanos = low.toNanos();
        long highNanos = high.toNanos();
        long span = highNanos - lowNanos;
        long offset = span <= 0 ? 0 : ThreadLocalRandom.current().nextLong(span);
        return Duration.ofNanos(lowNanos + offset);
    }

}
