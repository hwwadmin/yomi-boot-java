package com.yomi.boot.java.component.captcha.web;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.component.captcha.exception.CaptchaException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 验证码处理失败响应
 * <p>
 * 必须显式早于全局异常处理器生效：全局处理器对 {@link Exception} 有兜底处理，
 * 未指定顺序时 {@link CaptchaException} 会被渲染为"服务器繁忙"（丢失 500 + 错误码语义）。
 * 组件仅提供该异常映射（不提供任何接口）。
 *
 * @author zwei
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CaptchaExceptionAdvice {

    @ExceptionHandler(CaptchaException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<Void> captchaException(CaptchaException ex) {
        // 响应体刻意不含内部细节，原因链只落日志，否则存储/渲染故障的 cause 无从查起
        log.warn("验证码处理失败: errorCode={}, message={}", ex.getErrorCode().getCode(), ex.getMessage(), ex);
        return Response.fail(ex.getCode(), ex.getErrorCode().getCode(), ex.getMessage());
    }

}
