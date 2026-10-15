package com.yomi.boot.java.component.captcha;

import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可推进的测试时钟（测试辅助）：Caffeine 的过期判定与 remove 都按它计时
 *
 * @author zwei
 */
public final class MutableTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
        return nanos.get();
    }

    public void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

}
