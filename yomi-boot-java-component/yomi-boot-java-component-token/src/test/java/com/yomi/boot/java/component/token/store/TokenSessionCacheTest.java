package com.yomi.boot.java.component.token.store;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内缓存的契约：TTL=0 整体关闭、条目按逐条 TTL 过期、容量有界（限制伪造 token 的内存占用）、
 * 条目 TTL = min(配置 TTL, 令牌剩余有效期)
 */
class TokenSessionCacheTest {

    @Test
    void disabledWhenTtlZero() {
        TokenSessionCache cache = new TokenSessionCache(Duration.ZERO);

        assertFalse(cache.isEnabled());
        cache.put("h1", "{}", 5_000);
        assertNull(cache.get("h1"), "关闭态读写作空操作");
        assertEquals(0, cache.estimatedSize());
    }

    @Test
    void entryTtlIsCappedByTokenRemainingLifetime() {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        TokenSessionCache cache = new TokenSessionCache(Duration.ofSeconds(5));

        assertEquals(5_000, cache.entryTtlMillis(now, now.plusSeconds(600)),
                "令牌还很久：用配置上限");
        assertEquals(3_000, cache.entryTtlMillis(now, now.plusSeconds(3)),
                "令牌快到期：条目 TTL 必须压到剩余有效期");
        assertEquals(0, cache.entryTtlMillis(now, now), "已到期不缓存");
        assertTrue(cache.entryTtlMillis(now, now.minusSeconds(1)) <= 0, "已过期不缓存");
        assertEquals(0, cache.entryTtlMillis(now, null), "缺过期时间不缓存");
    }

    @Test
    void entryExpiresAfterTtl() throws InterruptedException {
        TokenSessionCache cache = new TokenSessionCache(Duration.ofMillis(120));

        cache.put("h1", "{\"userId\":1}", 120);
        assertEquals("{\"userId\":1}", cache.get("h1"));
        Thread.sleep(250);
        assertNull(cache.get("h1"), "条目必须按逐条 TTL 过期（窗口的来源）");
    }

    @Test
    void blankPayloadAndNullAreNotCached() {
        TokenSessionCache cache = new TokenSessionCache(Duration.ofSeconds(5));

        cache.put("h1", null, 5_000);
        cache.put("h2", "   ", 5_000);
        cache.put(null, "{}", 5_000);
        cache.put("h3", "{}", 0);

        assertEquals(0, cache.estimatedSize(), "miss（空值）与非正 TTL 不进缓存");
    }

    @Test
    void invalidateRemovesEntry() {
        TokenSessionCache cache = new TokenSessionCache(Duration.ofSeconds(5));
        cache.put("h1", "{}", 5_000);

        cache.invalidate("h1");

        assertNull(cache.get("h1"));
    }

    @Test
    void entryCountStaysBounded() {
        TokenSessionCache cache = new TokenSessionCache(Duration.ofMinutes(10));
        for (int i = 0; i < 10_050; i++) {
            cache.put("h" + i, "{}", 600_000);
        }
        assertTrue(cache.estimatedSize() <= 10_000, "条目数必须有界，实际=" + cache.estimatedSize());
    }
}
