package com.yomi.boot.java.component.token.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 令牌会话的进程内缓存（逐条 TTL + 固定容量，只缓存会话 JSON）
 * <p>
 * 条目 TTL = {@code min(配置 TTL, 令牌剩余有效期)}，本地条目不会比令牌活得久。
 * 跨实例失效由 {@link TokenInvalidationChannel} 广播完成：撤销时先清本实例条目再发通知，
 * 通知最多一次、丢失时其它实例最迟到条目 TTL 生效；{@code TokenService.validate} 命中后
 * 会重新校验 {@code isValid}/{@code isExpired}，判定结果仍然准确。
 * <p>
 * 缓存内容为会话 JSON 字符串，不使用 {@link com.yomi.boot.java.component.token.session.TokenSession} 对象：
 * 该对象可变（{@code @Data} + 可变 {@code context}），直接缓存会让调用方改到缓存里的同一实例，
 * 因此命中后重新解析，每次 load 都返回新实例。miss 不写缓存，伪造 token 不会留下缓存条目
 *
 * @author zwei
 */
public class TokenSessionCache {

    /** 容量上限，限制大量伪造 token 造成的内存占用 */
    private static final int MAX_ENTRIES = 10_000;

    /** 配置的 TTL 上限（{@code yomi.token.local-cache-ttl}） */
    private final Duration configuredTtl;

    /** 是否启用；TTL 为 0 时关闭 */
    private final boolean enabled;

    /** 键=令牌散列（sha256） → 值=会话 JSON + 该条目的 TTL */
    private final Cache<String, CachedSession> cache;

    public TokenSessionCache(Duration ttl) {
        this.configuredTtl = ttl == null ? Duration.ZERO : ttl;
        this.enabled = configuredTtl.toMillis() > 0;
        this.cache = enabled
                ? Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                // 容量淘汰在调用线程内完成：默认走公共线程池是异步的，条目数会短暂越界
                .executor(Runnable::run)
                .expireAfter(new Expiry<String, CachedSession>() {
                    @Override
                    public long expireAfterCreate(String key, CachedSession value, long currentTime) {
                        return TimeUnit.MILLISECONDS.toNanos(value.ttlMillis());
                    }

                    @Override
                    public long expireAfterUpdate(String key, CachedSession value, long currentTime,
                                                  long currentDuration) {
                        return TimeUnit.MILLISECONDS.toNanos(value.ttlMillis());
                    }

                    @Override
                    public long expireAfterRead(String key, CachedSession value, long currentTime,
                                                long currentDuration) {
                        return currentDuration;
                    }
                })
                .build()
                : null;
    }

    /**
     * 计算条目 TTL：取配置上限与"令牌剩余有效期"的较小者。
     *
     * @return 毫秒；{@code <= 0} 表示不值得缓存（已过期或时间字段缺失）
     */
    long entryTtlMillis(Instant now, Instant expireAt) {
        if (expireAt == null || now == null) {
            return 0;
        }
        return Math.min(configuredTtl.toMillis(), Duration.between(now, expireAt).toMillis());
    }

    /** 读取缓存；未启用、未命中或已过期返回 null */
    String get(String tokenHash) {
        if (!enabled) {
            return null;
        }
        CachedSession cached = cache.getIfPresent(tokenHash);
        return cached == null ? null : cached.json();
    }

    /** 写入缓存；空值或非正 TTL 不写 */
    void put(String tokenHash, String json, long ttlMillis) {
        if (!enabled || tokenHash == null || json == null || json.isBlank() || ttlMillis <= 0) {
            return;
        }
        cache.put(tokenHash, new CachedSession(json, ttlMillis));
    }

    /** 失效单个令牌条目（删除、单点登录替换、续期路径同步调用） */
    void invalidate(String tokenHash) {
        if (enabled && tokenHash != null) {
            cache.invalidate(tokenHash);
        }
    }

    /** 是否启用（装配日志与调用方判空用） */
    boolean isEnabled() {
        return enabled;
    }

    /** 当前条目数（测试观测） */
    long estimatedSize() {
        return enabled ? cache.estimatedSize() : 0;
    }

    /**
     * 缓存条目：会话 JSON + 该条目的 TTL（逐条，因令牌剩余有效期各不相同）
     */
    private record CachedSession(String json, long ttlMillis) {
    }
}
