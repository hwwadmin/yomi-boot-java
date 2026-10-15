package com.yomi.boot.java.component.rbac.web;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.component.rbac.exception.RbacAuthException;
import com.yomi.boot.java.component.token.ITokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;


/**
 * RBAC 鉴权失败响应（403）
 *
 * @author zwei
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class RbacExceptionAdvice {

    /** 可选 Token 服务，用于 401 时清理会话 */
    private final ObjectProvider<ITokenService> tokenServiceProvider;

    @ExceptionHandler(RbacAuthException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> rbacAuthException(RbacAuthException ex, HttpServletRequest request) {
        Long userId = resolveUserId();
        String uri = request == null ? "" : request.getRequestURI();
        log.warn("RBAC 鉴权失败: userId={}, uri={}, errorCode={}, message={}",
                userId, uri, ex.getErrorCode().getCode(), ex.getMessage());
        return Response.fail(ex.getCode(), ex.getErrorCode().getCode(), ex.getMessage());
    }

    /** 从令牌上下文解析用户 ID */
    private Long resolveUserId() {
        ITokenService tokenService = tokenServiceProvider.getIfAvailable();
        return tokenService == null ? null : tokenService.getUserId();
    }

}
