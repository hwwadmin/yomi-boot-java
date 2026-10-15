package com.yomi.boot.java.component.captcha.exception;

import com.yomi.boot.java.base.exception.AbstractRuntimeException;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 验证码基础设施异常
 * <p>
 * 响应码来自 {@link CaptchaErrorCode#getResponseCode()}（500）：校验/生成过程故障对客户端
 * 是"诚实失败"——不放行、也不伪装成"验证码错误"，业务需要降级放行时显式 catch。
 * 由组件的 {@code CaptchaExceptionAdvice} 统一渲染（非 Servlet 应用落全局异常处理器兜底）。
 *
 * @author zwei
 */
@Getter
public class CaptchaException extends AbstractRuntimeException {

    /** 对外错误码 */
    private final CaptchaErrorCode errorCode;

    private CaptchaException(CaptchaErrorCode errorCode, String detailMessage, Throwable cause) {
        super(errorCode.getResponseCode(), StringUtils.defaultIfBlank(detailMessage, errorCode.getMessage()), cause);
        this.errorCode = errorCode;
    }

    /** 按错误码创建异常（使用枚举默认提示） */
    public static CaptchaException of(CaptchaErrorCode errorCode) {
        return new CaptchaException(errorCode, null, null);
    }

    /** 按错误码创建异常，可覆盖提示并保留原因链 */
    public static CaptchaException of(CaptchaErrorCode errorCode, String detailMessage, Throwable cause) {
        return new CaptchaException(errorCode, detailMessage, cause);
    }

}
