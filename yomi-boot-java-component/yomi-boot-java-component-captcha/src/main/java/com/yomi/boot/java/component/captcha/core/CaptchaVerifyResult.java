package com.yomi.boot.java.component.captcha.core;

/**
 * 校验结果
 * <p>
 * 三种结果都是一次性消费的正常业务结果（校验后挑战即失效）；基础设施故障
 * 以 {@link com.yomi.boot.java.component.captcha.exception.CaptchaException} 抛出。
 *
 * @author zwei
 */
public enum CaptchaVerifyResult {

    /** 校验通过 */
    OK,

    /** 答案不匹配（挑战已被消费，需重新获取） */
    MISMATCH,

    /** 挑战不存在、已过期或已被消费 */
    EXPIRED,
    ;

}
