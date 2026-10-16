package com.yomi.boot.java.component.token.store;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.token.config.TokenProperties;
import com.yomi.boot.java.component.token.session.TokenSession;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储层与进程内薄缓存的接线契约：
 * <ol>
 *   <li>第二次读取命中缓存，不再产生 Redis 往返；命中返回新解析实例（不共享可变 TokenSession）；</li>
 *   <li>条目按 TTL 过期后回源，且 TTL 被令牌剩余有效期截断；{@code local-cache-ttl=0} 时行为与无缓存完全一致；</li>
 *   <li>撤销在同一实例上立即清条目、续期也清（避免命中旧 expireAt 被误判过期）</li>
 * </ol>
 * 真实 Redis 语义由 gated 的 {@code TokenIntegrationTest} 覆盖
 */
class RedisTokenStoreLocalCacheTest {

    private static final Long USER_ID = 9L;

    private static final TokenKeys KEYS = new TokenKeys("yomi:test:token");

    private static final String TOKEN = "tok-0123456789";

    @Test
    void secondLoadServedFromLocalCacheWithoutRoundTrips() {
        SessionTemplate template = new SessionTemplate();
        template.seed(TOKEN, session(Duration.ofMinutes(10)));
        RedisTokenStore store = store(template, Duration.ofSeconds(5));

        TokenSession first = store.load(TOKEN);
        int afterFirst = template.gets;
        TokenSession second = store.load(TOKEN);

        assertNotNull(first);
        assertEquals(2, afterFirst, "首次读取：路由键 + 快照各一次 GET");
        assertEquals(afterFirst, template.gets, "命中进程内缓存不得再打 Redis");
        assertNotSame(first, second, "缓存存 JSON，命中必须返回新解析实例（不得共享可变 TokenSession）");
        assertEquals(USER_ID, second.getUserId());
    }

    @Test
    void entryExpiresByTtlAndFallsBackToRedis() throws InterruptedException {
        SessionTemplate template = new SessionTemplate();
        template.seed(TOKEN, session(Duration.ofMinutes(10)));
        RedisTokenStore store = store(template, Duration.ofMillis(150));

        assertNotNull(store.load(TOKEN));
        assertNotNull(store.load(TOKEN));
        assertEquals(2, template.gets, "TTL 内命中缓存");

        Thread.sleep(300);

        assertNotNull(store.load(TOKEN));
        assertEquals(4, template.gets, "TTL 到点后必须回源（5 秒窗口的来源）");
    }

    @Test
    void entryTtlCappedBySessionRemainingLifetime() throws InterruptedException {
        SessionTemplate template = new SessionTemplate();
        // 令牌只剩 150ms，而配置 TTL 是 30 秒：条目必须按剩余有效期过期
        template.seed(TOKEN, session(Duration.ofMillis(150)));
        RedisTokenStore store = store(template, Duration.ofSeconds(30));

        assertNotNull(store.load(TOKEN));
        assertNotNull(store.load(TOKEN));
        assertEquals(2, template.gets, "剩余有效期内命中缓存");

        Thread.sleep(300);

        assertNotNull(store.load(TOKEN));
        assertEquals(4, template.gets, "条目 TTL 必须 ≤ 令牌剩余有效期，不能按 30 秒配置活满");
    }

    @Test
    void disabledCacheAlwaysHitsRedis() {
        SessionTemplate template = new SessionTemplate();
        template.seed(TOKEN, session(Duration.ofMinutes(10)));
        RedisTokenStore store = store(template, Duration.ZERO);

        assertNotNull(store.load(TOKEN));
        assertNotNull(store.load(TOKEN));

        assertEquals(4, template.gets, "local-cache-ttl=0 时行为与无缓存一致");
    }

    @Test
    void revokeEvictsLocalEntryImmediately() {
        SessionTemplate template = new SessionTemplate();
        template.seed(TOKEN, session(Duration.ofMinutes(10)));
        RedisTokenStore store = store(template, Duration.ofSeconds(5));

        assertNotNull(store.load(TOKEN));
        assertNotNull(store.load(TOKEN));
        assertEquals(2, template.gets, "前提：第二次已命中缓存");

        store.delete(TOKEN);

        assertNull(store.load(TOKEN), "撤销后本实例条目已清、Redis 路由键已删");
        assertEquals(4, template.gets, "撤销后的读取必须回源（证明条目清掉了）：2 初始 + 1 删前路由读 + 1 回源");
    }

    @Test
    void renewEvictsLocalEntry() {
        SessionTemplate template = new SessionTemplate();
        TokenSession session = session(Duration.ofMinutes(10));
        template.seed(TOKEN, session);
        RedisTokenStore store = store(template, Duration.ofSeconds(5));

        assertNotNull(store.load(TOKEN));
        assertEquals(2, template.gets);

        assertTrue(store.renewIfPresent(TOKEN, session), "脚本返回 1 视为续期成功");
        int afterRenew = template.gets;

        assertNotNull(store.load(TOKEN));
        assertEquals(afterRenew + 2, template.gets, "续期改了 expireAt，旧条目必须清掉（否则可能命中旧 expireAt 被误判过期）");
    }

    private static TokenSession session(Duration remaining) {
        Instant now = Instant.now();
        TokenSession session = new TokenSession();
        session.setUserId(USER_ID);
        session.setIssuedAt(now);
        session.setExpireAt(now.plus(remaining));
        return session;
    }

    private static RedisTokenStore store(SessionTemplate template, Duration localCacheTtl) {
        TokenProperties properties = new TokenProperties();
        properties.setLocalCacheTtl(localCacheTtl);
        return new RedisTokenStore(template, KEYS, properties);
    }

    /** 内存版模板桩：记录 GET 次数，按 KV 读写驱动 load/delete/renew */
    static class SessionTemplate extends StringRedisTemplate {

        final Map<String, String> values = new HashMap<>();

        int gets;

        SessionTemplate() {
            super(proxy(RedisConnectionFactory.class));
        }

        void seed(String token, TokenSession session) {
            String hash = TokenKeys.hashToken(token);
            values.put(KEYS.routeKeyByStorageToken(hash), String.valueOf(session.getUserId()));
            values.put(KEYS.sessionKeyByStorageToken(session.getUserId(), hash), JsonUtils.toJson(session));
        }

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "get" -> {
                            gets++;
                            yield values.get(String.valueOf(args[0]));
                        }
                        case "set" -> {
                            values.put(String.valueOf(args[0]), String.valueOf(args[1]));
                            yield null;
                        }
                        default -> null;
                    });
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            return (T) Long.valueOf(1L);
        }

        @Override
        public Long delete(Collection<String> keys) {
            keys.forEach(values::remove);
            return (long) keys.size();
        }

        @Override
        public Boolean delete(String key) {
            return values.remove(key) != null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public SetOperations<String, String> opsForSet() {
            return (SetOperations<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SetOperations.class},
                    (proxy, method, args) -> "members".equals(method.getName()) ? Set.of() : null);
        }

        private static RedisConnectionFactory proxy(Class<RedisConnectionFactory> type) {
            return type.cast(Proxy.newProxyInstance(RedisTokenStoreLocalCacheTest.class.getClassLoader(),
                    new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "stub";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                        default -> null;
                    }));
        }
    }
}
