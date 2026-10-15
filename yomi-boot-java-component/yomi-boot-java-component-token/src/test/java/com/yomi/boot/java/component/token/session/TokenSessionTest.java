package com.yomi.boot.java.component.token.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌会话快照纯逻辑测试（无需 Redis）
 *
 * @author zwei
 */
class TokenSessionTest {

    @Test
    void isValidRequiresUserIdAndIssuedAt() {
        TokenSession session = new TokenSession();
        assertFalse(session.isValid());

        session.setUserId(0L);
        session.setIssuedAt(Instant.now());
        assertFalse(session.isValid());

        session.setUserId(1001L);
        assertTrue(session.isValid());

        session.setIssuedAt(null);
        assertFalse(session.isValid());
    }

    @Test
    void expireAtNullOrPastMeansExpired() {
        TokenSession session = new TokenSession();
        assertTrue(session.isExpired());

        session.setExpireAt(Instant.now().minusSeconds(1));
        assertTrue(session.isExpired());

        session.setExpireAt(Instant.now().plusSeconds(60));
        assertFalse(session.isExpired());
    }

}
