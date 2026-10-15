package com.yomi.boot.java.component.token.web;

import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.exception.TokenAuthException;
import com.yomi.boot.java.component.token.exception.TokenErrorCode;
import com.yomi.boot.java.component.token.session.TokenSession;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 错误转发（{@link DispatcherType#ERROR}，如 404/500 转发到 {@code /error}）必须放行：
 * 否则真实状态会被改写成"缺少访问令牌"，客户端把"接口不存在/服务出错"误判为"没登录"
 */
class TokenInterceptorDispatchTest {

    private final TokenInterceptor interceptor = new TokenInterceptor(new NullUserIdITokenService());

    @Test
    void errorDispatchIsPassedThroughWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()),
                "错误转发不得要求令牌，否则 404/500 被改写成 TOKEN_REQUIRED");
    }

    @Test
    void normalDispatchStillRequiresToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setDispatcherType(DispatcherType.REQUEST);

        TokenAuthException ex = assertThrows(TokenAuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(TokenErrorCode.REQUIRED, ex.getErrorCode());
    }

    /** 只用于满足构造器；错误转发与缺令牌路径都不会触达令牌服务 */
    static class NullUserIdITokenService implements ITokenService {

        @Override
        public String issue(TokenSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenSession validate(String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revoke(String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revokeByUserId(Long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TokenSession> listByUserId(Long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long getUserId() {
            return null;
        }
    }

}
