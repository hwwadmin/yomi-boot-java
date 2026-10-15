package com.yomi.boot.java.component.rbac.web;

import com.yomi.boot.java.component.rbac.IRbacAuthService;
import com.yomi.boot.java.component.rbac.config.RbacProperties;
import com.yomi.boot.java.component.rbac.model.RbacPermModel;
import com.yomi.boot.java.component.rbac.exception.RbacAuthException;
import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.session.TokenSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * context-path 部署的路径匹配：权限通常按应用内路径登记（/api/x），而 REQUEST_URI 含前缀（/app/api/x）；
 * 首路径未命中时，再按去掉 context-path 的路径匹配
 */
class RbacInterceptorContextPathTest {

    @Test
    void stripsContextPathWhenFirstMatchDenied() {
        RecordingAuthService authService = new RecordingAuthService("/api/x");
        RbacInterceptor interceptor = interceptor(authService);
        MockHttpServletRequest request = request("/app", "/app/api/x");

        assertDoesNotThrow(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(List.of("/app/api/x", "/api/x"), authService.queried,
                "应先用原样路径、被拒后再用去掉 context-path 的路径各查一次");
    }

    @Test
    void withoutContextPathQueriesOnce() {
        RecordingAuthService authService = new RecordingAuthService(null);
        RbacInterceptor interceptor = interceptor(authService);

        assertThrows(RbacAuthException.class, () -> interceptor.preHandle(
                request("", "/api/x"), new MockHttpServletResponse(), new Object()));

        assertEquals(List.of("/api/x"), authService.queried, "无 context-path 时不应重复查询");
    }

    /** 权限按带 context-path 的路径登记时，首路径匹配即通过 */
    @Test
    void prefixedPermissionStillMatchesFirstTime() {
        RecordingAuthService authService = new RecordingAuthService("/app/api/x");
        RbacInterceptor interceptor = interceptor(authService);

        assertDoesNotThrow(() -> interceptor.preHandle(
                request("/app", "/app/api/x"), new MockHttpServletResponse(), new Object()));

        assertEquals(List.of("/app/api/x"), authService.queried);
    }

    /** 构造带 path 的 HttpServletRequest 桩 */
    private static MockHttpServletRequest request(String contextPath, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath(contextPath);
        request.setRequestURI(uri);
        return request;
    }

    /** 构造 RBAC 拦截器实例 */
    private static RbacInterceptor interceptor(IRbacAuthService authService) {
        return new RbacInterceptor(new LoginITokenService(), authService, new RbacProperties());
    }

    /** 记录被查询的路径；只放行 allowedPath */
    static class RecordingAuthService implements IRbacAuthService {

        final List<String> queried = new ArrayList<>();
        private final String allowedPath;

        RecordingAuthService(String allowedPath) {
            this.allowedPath = allowedPath;
        }

        @Override
        public boolean canAccessHttp(long userId, String method, String path) {
            queried.add(path);
            return path.equals(allowedPath);
        }

        @Override
        public List<RbacPermModel> listUiMenus(long userId) {
            return List.of();
        }

        @Override
        public void refreshProjection() {
        }
    }

    static class LoginITokenService implements ITokenService {

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
            return 100L;
        }
    }

}
