package com.yomi.boot.java.component.captcha.core;

import java.time.Instant;
import java.util.Base64;

/**
 * 验证码挑战（生成结果）：只含图片与句柄，不含答案
 *
 * @param captchaId   挑战句柄（128 位随机值，随图片一起下发）
 * @param type        挑战类型
 * @param imageBase64 PNG 图片的 base64（裸串，JSON 安全）
 * @param expireAt    过期时间（此后校验返回 EXPIRED）
 * @author zwei
 */
public record CaptchaChallenge(String captchaId, CaptchaType type, String imageBase64, Instant expireAt) {

    /**
     * 图片 data URI，前端可直接作为 {@code <img src>}
     */
    public String dataUri() {
        return "data:image/png;base64," + imageBase64;
    }

    /**
     * 还原 PNG 字节，供直接响应 {@code image/png} 的接口使用
     */
    public byte[] png() {
        return Base64.getDecoder().decode(imageBase64);
    }

}
