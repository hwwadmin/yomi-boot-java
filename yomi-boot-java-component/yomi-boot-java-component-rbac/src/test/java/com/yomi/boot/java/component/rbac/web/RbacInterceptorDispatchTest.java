package com.yomi.boot.java.component.rbac.web;

import com.yomi.boot.java.component.rbac.IRbacAuthService;
import com.yomi.boot.java.component.rbac.config.RbacProperties;
import com.yomi.boot.java.component.rbac.model.RbacPermModel;
import com.yomi.boot.java.component.rbac.exception.RbacAuthException;
import com.yomi.boot.java.component.rbac.exception.RbacErrorCode;
import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.session.TokenSession;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rbac 拦截器对错误转发（{@link DispatcherType#ERROR}）的放行：
 * 404/500 转发到 {@code /error} 时保留原始状态，不被改写成 FORBIDDEN（未登录）或"无访问权限"（/error 没登记权限）
 */
class RbacInterceptorDispatchTest {

    /** 未登录（getUserId 返回 null）的令牌服务桩：REQUEST 分发应被 rbac 判为 FORBIDDEN */
    private final RbacInterceptor interceptor = new RbacInterceptor(new NoLoginITokenService(), new NoopAuthService(),
            new RbacProperties());

    static class NoopAuthService implements IRbacAuthService {

        @Override
        public boolean canAccessHttp(long userId, String method, String path) {
            throw new UnsupportedOperationException("错误转发放行路径不应触达鉴权求值");
        }

        @Override
        public List<RbacPermModel> listUiMenus(long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void refreshProjection() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void errorDispatchIsPassedThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()),
                "错误转发不得参与鉴权，否则真实状态被改写成鉴权错误");
    }

    @Test
    void normalDispatchStillRejectsAnonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.setDispatcherType(DispatcherType.REQUEST);

        RbacAuthException ex = assertThrows(RbacAuthException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(RbacErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    static class NoLoginITokenService implements ITokenService {

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
