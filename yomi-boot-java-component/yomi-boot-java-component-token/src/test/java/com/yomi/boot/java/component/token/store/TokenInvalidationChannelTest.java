package com.yomi.boot.java.component.token.store;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失效通知通道的契约：
 * <ol>
 *   <li>发布只发通道名 + 令牌散列，接收侧按散列清本实例条目；</li>
 *   <li>发布失败/报文异常一律吞掉（撤销本身已生效，靠条目 TTL 兜底）；</li>
 *   <li>进程内缓存关闭时整层空转</li>
 * </ol>
 */
class TokenInvalidationChannelTest {

    private static final String CHANNEL = "yomi:test:token:invalidate";

    @Test
    void publishSendsChannelAndTokenHash() {
        CapturingTemplate template = new CapturingTemplate();
        TokenSessionCache cache = new TokenSessionCache(Duration.ofSeconds(30));
        TokenInvalidationChannel channel = new TokenInvalidationChannel(template, CHANNEL, cache);

        assertTrue(channel.isEnabled());
        assertEquals(CHANNEL, channel.getChannel());
        channel.publish("hash-a");

        assertEquals(List.of(CHANNEL + "|hash-a"), template.published);
    }

    @Test
    void publishFailureIsSwallowed() {
        CapturingTemplate template = new CapturingTemplate();
        template.failPublish = true;
        TokenInvalidationChannel channel =
                new TokenInvalidationChannel(template, CHANNEL, new TokenSessionCache(Duration.ofSeconds(30)));

        assertDoesNotThrow(() -> channel.publish("hash-a"), "发布失败不得影响撤销本身");
    }

    @Test
    void onMessageEvictsCachedEntryOfThatHashOnly() {
        CapturingTemplate template = new CapturingTemplate();
        TokenSessionCache cache = new TokenSessionCache(Duration.ofSeconds(30));
        TokenInvalidationChannel channel = new TokenInvalidationChannel(template, CHANNEL, cache);
        cache.put("h1", "{}", 30_000);
        cache.put("h2", "{}", 30_000);

        channel.onMessage(message("h1"), null);

        assertNull(cache.get("h1"), "收到通知必须清掉该散列的条目");
        assertEquals("{}", cache.get("h2"), "其它令牌不受影响");
    }

    @Test
    void onMessageNeverThrowsOnGarbage() {
        CapturingTemplate template = new CapturingTemplate();
        TokenInvalidationChannel channel =
                new TokenInvalidationChannel(template, CHANNEL, new TokenSessionCache(Duration.ofSeconds(30)));

        assertDoesNotThrow(() -> channel.onMessage(null, null));
        assertDoesNotThrow(() -> channel.onMessage(message("   "), null));
        // pub/sub 没有 PEL：异常吞掉即可，不该抛出去
        assertDoesNotThrow(() -> channel.onMessage(message("h1"), null));
    }

    @Test
    void disabledCacheMakesChannelNoOp() {
        CapturingTemplate template = new CapturingTemplate();
        TokenInvalidationChannel channel =
                new TokenInvalidationChannel(template, CHANNEL, new TokenSessionCache(Duration.ZERO));

        assertFalse(channel.isEnabled());
        channel.publish("hash-a");

        assertEquals(List.of(), template.published, "缓存关闭时不发通知");
    }

    private static Message message(String body) {
        return new Message() {
            @Override
            public byte[] getBody() {
                return body.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public byte[] getChannel() {
                return CHANNEL.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    /** 捕获 convertAndSend 的模板桩 */
    static class CapturingTemplate extends StringRedisTemplate {

        final List<String> published = new ArrayList<>();

        boolean failPublish;

        @Override
        public Long convertAndSend(String channel, Object message) {
            if (failPublish) {
                throw new IllegalStateException("redis down");
            }
            published.add(channel + "|" + message);
            return 1L;
        }
    }
}
