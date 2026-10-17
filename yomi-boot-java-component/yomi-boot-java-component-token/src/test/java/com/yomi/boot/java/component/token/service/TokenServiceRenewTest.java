package com.yomi.boot.java.component.token.service;

import com.yomi.boot.java.component.token.config.TokenProperties;
import com.yomi.boot.java.component.token.generator.TokenGenerator;
import com.yomi.boot.java.component.token.session.TokenSession;
import com.yomi.boot.java.component.token.store.TokenKeys;
import com.yomi.boot.java.component.token.store.TokenStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 滑动续期的两道约束（离线）：
 * <ol>
 *   <li>续期以 {@code issuedAt + max-lifetime}（默认 1 天）为上限，达上限时不写回</li>
 *   <li>写回走原子守卫：会话已被注销/踢出时保持删除状态、内存态还原</li>
 * </ol>
 */
class TokenServiceRenewTest {

    private static final Duration TTL = Duration.ofHours(2);

    @Test
    void renewExtendsWithinCap() {
        StubStore store = new StubStore();
        // 既有会话：签发已 1 分钟、剩余有效期 9 分钟，续期（ttl=2h）能实际延长
        Instant issuedAt = Instant.now().minus(Duration.ofMinutes(1));
        TokenSession session = session(7L, issuedAt, issuedAt.plus(Duration.ofMinutes(10)));
        Instant previousExpireAt = session.getExpireAt();
        store.save("t-1", session);
        TokenService service = service(store, TTL, Duration.ofDays(1));

        TokenSession validated = service.validate("t-1");

        assertTrue(validated.getExpireAt().isAfter(previousExpireAt), "能延长时必须延长");
        assertClose(Instant.now().plus(TTL), validated.getExpireAt(), "未到上限时按 ttl 正常续期");
        assertClose(Instant.now().plus(TTL), store.load("t-1").getExpireAt(), "写回的也是 ttl 续期值");
        assertEquals(1, store.renewCalls);
    }

    @Test
    void renewCappedAtMaxLifetime() {
        StubStore store = new StubStore();
        Duration maxLifetime = Duration.ofHours(1);
        Instant issuedAt = Instant.now().minus(Duration.ofMinutes(55));
        // 当前仍在有效期内，但 now + ttl 会越过 issuedAt + maxLifetime
        TokenSession session = session(7L, issuedAt, Instant.now().plus(Duration.ofMinutes(2)));
        store.save("t-2", session);
        TokenService service = service(store, Duration.ofMinutes(10), maxLifetime);

        TokenSession validated = service.validate("t-2");

        assertEquals(issuedAt.plus(maxLifetime), validated.getExpireAt(), "续期必须被钉在 issuedAt + max-lifetime");
        assertEquals(issuedAt.plus(maxLifetime), store.load("t-2").getExpireAt(), "写回的也必须是上限值");
    }

    @Test
    void noWriteWhenAlreadyAtCap() {
        StubStore store = new StubStore();
        Instant issuedAt = Instant.now().minus(Duration.ofDays(2));
        Instant originalExpireAt = Instant.now().plus(TTL);
        TokenSession session = session(7L, issuedAt, originalExpireAt);
        store.save("t-3", session);
        TokenService service = service(store, TTL, Duration.ofDays(1));

        TokenSession validated = service.validate("t-3");

        assertEquals(originalExpireAt, validated.getExpireAt(), "已越过上限：不延长");
        assertEquals(0, store.renewCalls, "不能延长时不应产生任何写回");
    }

    @Test
    void doesNotResurrectWhenStoreRefuses() {
        StubStore store = new StubStore();
        Instant issuedAt = Instant.now().minus(Duration.ofMinutes(1));
        Instant originalExpireAt = issuedAt.plus(Duration.ofMinutes(10));
        TokenSession session = session(7L, issuedAt, originalExpireAt);
        store.save("t-4", session);
        store.refuseRenew = true; // 模拟写回时发现会话已被注销/踢出
        TokenService service = service(store, TTL, Duration.ofDays(1));

        TokenSession validated = service.validate("t-4");

        assertEquals(originalExpireAt, validated.getExpireAt(),
                "守卫拒绝续期时内存态必须还原，调用方不能拿到'已被延长'的假象");
        assertEquals(1, store.renewCalls);
    }

    @Test
    void zeroMaxLifetimeMeansUnlimited() {
        StubStore store = new StubStore();
        Instant issuedAt = Instant.now().minus(Duration.ofDays(10));
        store.save("t-5", session(7L, issuedAt, Instant.now().plus(Duration.ofMinutes(30))));
        TokenService service = service(store, TTL, Duration.ZERO);

        TokenSession validated = service.validate("t-5");

        assertClose(Instant.now().plus(TTL), validated.getExpireAt(), "0 表示不限制，按 ttl 续期");
    }

    @Test
    void maxLifetimeDefaultsToOneDay() {
        assertEquals(Duration.ofDays(1), new TokenProperties().getMaxLifetime());
    }

    @Test
    void maxLifetimeValidation() {
        TokenProperties properties = new TokenProperties();
        properties.setTtl(Duration.ofHours(2));

        properties.setMaxLifetime(Duration.ofMinutes(30));
        IllegalStateException belowTtl = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(belowTtl.getMessage().contains("max-lifetime"), belowTtl.getMessage());

        properties.setMaxLifetime(Duration.ofHours(-1));
        assertThrows(IllegalStateException.class, properties::validate);

        properties.setMaxLifetime(Duration.ofDays(1));
        properties.validate();

        properties.setMaxLifetime(Duration.ZERO);
        properties.validate();
    }

    /** 构造被测 Service 实例 */
    private static TokenService service(StubStore store, Duration ttl, Duration maxLifetime) {
        TokenProperties properties = new TokenProperties();
        properties.setEnabled(true);
        properties.setTtl(ttl);
        properties.setRenewOnValidate(true);
        properties.setMaxLifetime(maxLifetime);
        properties.setStorePrefix("yomi:test:token");
        return new TokenService(new FixedTokenGenerator(), store, properties, new TokenKeys("yomi:test:token"));
    }

    /** 构造测试会话 */
    private static TokenSession session(Long userId, Instant issuedAt, Instant expireAt) {
        TokenSession session = new TokenSession();
        session.setUserId(userId);
        session.setIssuedAt(issuedAt);
        session.setExpireAt(expireAt);
        return session;
    }

    /** 断言两个 Instant 相差不超过 5 秒 */
    private static void assertClose(Instant expected, Instant actual, String message) {
        assertTrue(Math.abs(Duration.between(expected, actual).toSeconds()) <= 5,
                message + "：expected≈" + expected + ", actual=" + actual);
    }

    static class FixedTokenGenerator implements TokenGenerator {

        @Override
        public String newToken() {
            return "fixed-token";
        }
    }

    /** 内存 TokenStore 桩：可编排"守卫拒绝续期" */
    static class StubStore implements TokenStore {

        final Map<String, TokenSession> sessions = new ConcurrentHashMap<>();
        int renewCalls;
        boolean refuseRenew;

        @Override
        public void save(String token, TokenSession session) {
            sessions.put(token, session);
        }

        @Override
        public TokenSession load(String token) {
            return sessions.get(token);
        }

        @Override
        public void delete(String token) {
            sessions.remove(token);
        }

        @Override
        public void deleteByUserId(Long userId) {
        }

        @Override
        public void replaceAllForUser(String token, TokenSession session) {
            sessions.put(token, session);
        }

        @Override
        public boolean renewIfPresent(String token, TokenSession session) {
            renewCalls++;
            if (refuseRenew || !sessions.containsKey(token)) {
                return false;
            }
            sessions.put(token, session);
            return true;
        }

        @Override
        public List<TokenSession> listByUserId(Long userId) {
            return List.of();
        }
    }

}
