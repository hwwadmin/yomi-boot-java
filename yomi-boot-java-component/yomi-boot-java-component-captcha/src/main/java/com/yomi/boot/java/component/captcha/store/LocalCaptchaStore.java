package com.yomi.boot.java.component.captcha.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;

/**
 * 本地内存存储（单实例）
 * <p>
 * Caffeine 承担 TTL 与容量上限：过期条目对读取与 remove 都不可见
 * （过期后 {@code asMap().remove} 返回 null），一次性消费语义与 Redis 一致。
 *
 * @author zwei
 */
public class LocalCaptchaStore implements CaptchaStore {

    /** captchaId → 答案，带写入 TTL 与容量上限 */
    private final Cache<String, String> cache;

    public LocalCaptchaStore(Duration ttl, int maxEntries) {
        this(ttl, maxEntries, Ticker.systemTicker());
    }

    public LocalCaptchaStore(Duration ttl, int maxEntries, Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(ttl)
                .ticker(ticker)
                // 同步维护：容量淘汰立即生效（默认异步执行器下淘汰会滞后），内存上限可预测
                .executor(Runnable::run)
                .build();
    }

    /** 写入挑战答案直至 TTL 或被淘汰 */
    @Override
    public void save(String captchaId, String answer) {
        cache.put(captchaId, answer);
    }

    /** 取出并删除条目，过期或不存在返回 null */
    @Override
    public String remove(String captchaId) {
        return cache.asMap().remove(captchaId);
    }

    /** 存储实现标识 {@code local} */
    @Override
    public String name() {
        return "local";
    }

}
