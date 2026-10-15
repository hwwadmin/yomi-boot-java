package com.yomi.boot.java.component.rbac.web;

import com.yomi.boot.java.component.rbac.IRbacAuthService;
import com.yomi.boot.java.component.rbac.config.RbacProperties;
import com.yomi.boot.java.component.rbac.enums.RbacHttpPathStyle;
import com.yomi.boot.java.component.rbac.exception.RbacAuthException;
import com.yomi.boot.java.component.rbac.exception.RbacErrorCode;
import com.yomi.boot.java.component.token.ITokenService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * RBAC HTTP 鉴权拦截器（需在令牌拦截器之后）
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class RbacInterceptor implements HandlerInterceptor {

    /** 解析当前登录用户 */
    private final ITokenService tokenService;
    /** HTTP 鉴权判定 */
    private final IRbacAuthService rbacAuthService;

    /** context-path 匹配策略等 */
    private final RbacProperties properties;

    /** 解析用户并校验接口权限 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            // 错误转发（404/500 → /error）不参与鉴权，避免真实状态被改写成 FORBIDDEN
            return true;
        }
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        Long userId = tokenService.getUserId();
        if (userId == null || userId <= 0) {
            throw RbacAuthException.of(RbacErrorCode.FORBIDDEN);
        }
        String method = request.getMethod();
        String path = resolvePath(request);
        boolean allowed = authorize(userId, method, path);
        if (!allowed) {
            // 带 context-path 部署时，再按去掉前缀后的路径匹配一次
            String contextStripped = resolveContextStrippedPath(request, path);
            if (contextStripped != null) {
                allowed = authorize(userId, method, contextStripped);
            }
        }
        if (!allowed) {
            throw RbacAuthException.of(RbacErrorCode.FORBIDDEN);
        }
        return true;
    }

    /** 调用 RBAC 服务判断是否允许访问 */
    private boolean authorize(long userId, String method, String path) {
        try {
            return rbacAuthService.canAccessHttp(userId, method, path);
        } catch (RbacAuthException e) {
            throw e;
        } catch (Exception e) {
            throw RbacAuthException.of(RbacErrorCode.EVAL_FAILED, null, e);
        }
    }

    /** 去掉 context-path 的候选路径；无 context-path 或去掉后为空则返回 null（不重复查询） */
    private static String resolveContextStrippedPath(HttpServletRequest request, String path) {
        String contextPath = request.getContextPath();
        if (StringUtils.isBlank(contextPath) || "/".equals(contextPath) || !path.startsWith(contextPath)) {
            return null;
        }
        String stripped = StringUtils.trimToEmpty(path.substring(contextPath.length()));
        return StringUtils.isBlank(stripped) || stripped.equals(path) ? null : stripped;
    }

    /** 解析请求路径用于鉴权 */
    private String resolvePath(HttpServletRequest request) {
        RbacHttpPathStyle style = properties.getHttpPathStyle();
        if (style == RbacHttpPathStyle.SERVLET_PATH) {
            return StringUtils.trimToEmpty(request.getServletPath());
        }
        return StringUtils.trimToEmpty(request.getRequestURI());
    }

}
