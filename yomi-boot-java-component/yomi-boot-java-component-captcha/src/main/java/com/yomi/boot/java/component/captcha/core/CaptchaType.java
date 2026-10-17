package com.yomi.boot.java.component.captcha.core;

import org.apache.commons.lang3.StringUtils;

/**
 * 验证码挑战类型
 *
 * @author zwei
 */
public enum CaptchaType {

    /** 读图输入：把图片上的字符输入回来 */
    TEXT,

    /** 看图算数：输入图片上算式的结果 */
    ARITHMETIC,
    ;

    /**
     * 宽松解析（接口入参、yml 均写 text/arithmetic，大小写不限）
     * <p>
     * 业务接口通常收到字符串（如 {@code ?type=text}），在这里统一解析成枚举，
     * 大小写不限，取值口径与 {@code yomi.captcha.default-type} 保持一致。
     */
    public static CaptchaType parse(String value, CaptchaType fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        for (CaptchaType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return fallback;
    }

}
