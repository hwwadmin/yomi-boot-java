package com.yomi.boot.java.component.token.session;

import com.yomi.boot.java.component.token.exception.TokenAuthException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 请求域会话门面测试
 */
class TokenContextTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getContextValueWithoutSessionReturnsNull() {
        assertNull(TokenContext.getContextValue("tenantId"));
        assertNull(TokenContext.getContextValue(null));
        assertNull(TokenContext.getContextValue(" "));
    }

    @Test
    void putAndGetContextValueOnBoundSession() {
        bindSession(new TokenSession());
        TokenContext.putContextValue("tenantId", 42L);
        assertEquals(42L, TokenContext.getContextValue("tenantId"));
    }

    @Test
    void putContextValueRequiresSession() {
        assertThrows(TokenAuthException.class, () -> TokenContext.putContextValue("k", "v"));
    }

    @Test
    void putContextValueRejectsNullValue() {
        bindSession(new TokenSession());
        assertThrows(IllegalArgumentException.class, () -> TokenContext.putContextValue("k", null));
    }

    private static void bindSession(TokenSession session) {
        session.setUserId(1L);
        session.setIssuedAt(Instant.now());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TokenContext.ATTR_SESSION, session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

}
