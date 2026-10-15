package com.yomi.boot.java.component.token.web;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.component.token.exception.TokenAuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 令牌鉴权失败响应
 * <p>
 * 必须显式早于全局异常处理器生效：全局处理器对 {@link Exception} 有兜底处理，
 * 未指定顺序时 {@link TokenAuthException} 可能被渲染为"服务器繁忙"。
 *
 * @author zwei
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TokenExceptionAdvice {

    @ExceptionHandler(TokenAuthException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> tokenAuthException(TokenAuthException ex) {
        log.warn("令牌鉴权失败: errorCode={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return Response.fail(ex.getCode(), ex.getErrorCode().getCode(), ex.getMessage());
    }

}
