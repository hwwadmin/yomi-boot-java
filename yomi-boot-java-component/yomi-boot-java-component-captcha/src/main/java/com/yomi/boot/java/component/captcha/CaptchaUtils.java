package com.yomi.boot.java.component.captcha;

import com.yomi.boot.java.component.captcha.core.CaptchaChallenge;
import com.yomi.boot.java.component.captcha.core.CaptchaType;
import com.yomi.boot.java.component.captcha.core.CaptchaVerifyResult;
import com.yomi.boot.java.core.utils.SpringUtils;

/**
 * 验证码工具类
 *
 * @author zwei
 */
public class CaptchaUtils {

    private CaptchaUtils() {

    }

    /**
     * 获取验证码服务
     */
    public static ICaptchaService getService() {
        return SpringUtils.getBean(ICaptchaService.class);
    }

    /**
     * 生成默认类型的验证码挑战
     */
    public static CaptchaChallenge generate() {
        return getService().generate(null);
    }

    /**
     * 生成指定类型的验证码挑战
     */
    public static CaptchaChallenge generate(CaptchaType type) {
        return getService().generate(type);
    }

    /**
     * 校验答案（一次性消费；基础设施异常抛 CaptchaException）
     */
    public static CaptchaVerifyResult verify(String captchaId, String answer) {
        return getService().verify(captchaId, answer);
    }

}
