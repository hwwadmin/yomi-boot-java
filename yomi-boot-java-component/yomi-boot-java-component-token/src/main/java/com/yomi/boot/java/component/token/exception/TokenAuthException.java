package com.yomi.boot.java.component.token.exception;

import com.yomi.boot.java.base.exception.AbstractRuntimeException;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 令牌鉴权失败异常
 * <p>
 * 响应码来自 {@link TokenErrorCode#getResponseCode()}（鉴权失败类为 401），
 * 由组件的 {@code TokenExceptionAdvice} 统一渲染，不经全局异常处理器。
 *
 * @author zwei
 */
@Getter
public class TokenAuthException extends AbstractRuntimeException {

    /** 对外错误码 */
    private final TokenErrorCode errorCode;

    private TokenAuthException(TokenErrorCode errorCode, String detailMessage, Throwable cause) {
        super(errorCode.getResponseCode(), StringUtils.defaultIfBlank(detailMessage, errorCode.getMessage()), cause);
        this.errorCode = errorCode;
    }

    /** 按错误码构造鉴权异常 */
    public static TokenAuthException of(TokenErrorCode errorCode) {
        return new TokenAuthException(errorCode, null, null);
    }

    /**
     * 按错误码构造鉴权异常
     *
     * @param detailMessage 补充说明（可空，默认取错误码文案）
     * @param cause         根因（可空）
     */
    public static TokenAuthException of(TokenErrorCode errorCode, String detailMessage, Throwable cause) {
        return new TokenAuthException(errorCode, detailMessage, cause);
    }

}
