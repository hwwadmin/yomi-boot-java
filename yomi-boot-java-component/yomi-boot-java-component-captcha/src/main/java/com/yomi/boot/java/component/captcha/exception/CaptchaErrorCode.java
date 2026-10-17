package com.yomi.boot.java.component.captcha.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import lombok.Getter;

/**
 * 验证码组件对外错误码
 * <p>
 * 响应码分层（与 {@link StatusCode} 码段约定一致）：
 * <ul>
 *     <li>500 基础设施故障：生成/校验/作废过程异常（如 Redis 不可用），不向客户端暴露细节；
 *         归在 500 段：前端据此与"验证码输入错误"区分，监控也与业务失败分开统计；</li>
 *     <li>输错（MISMATCH）与过期（EXPIRED）是正常业务结果，
 *         经 {@link com.yomi.boot.java.component.captcha.core.CaptchaVerifyResult} 返回，不占错误码。</li>
 * </ul>
 *
 * @author zwei
 */
@Getter
public enum CaptchaErrorCode {

    /** 验证码生成失败（图片渲染或存储过程基础设施异常） */
    GENERATE_FAILED("CAPTCHA_GENERATE_FAILED", StatusCode.serverError.getCode(), "验证码生成失败"),
    /** 验证码校验失败（校验过程基础设施异常；与"输错/过期"区分，不向客户端暴露细节） */
    VERIFY_FAILED("CAPTCHA_VERIFY_FAILED", StatusCode.serverError.getCode(), "验证码校验失败"),
    /** 验证码作废失败（作废过程基础设施异常） */
    INVALIDATE_FAILED("CAPTCHA_INVALIDATE_FAILED", StatusCode.serverError.getCode(), "验证码作废失败"),
    ;

    /** 对外错误码 */
    private final String code;

    /** 响应码 */
    private final int responseCode;

    /** 默认提示消息 */
    private final String message;

    CaptchaErrorCode(String code, int responseCode, String message) {
        this.code = code;
        this.responseCode = responseCode;
        this.message = message;
    }

}
