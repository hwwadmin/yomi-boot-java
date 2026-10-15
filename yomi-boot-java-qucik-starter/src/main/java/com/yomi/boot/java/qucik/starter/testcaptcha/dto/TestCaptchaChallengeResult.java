package com.yomi.boot.java.qucik.starter.testcaptcha.dto;

import com.yomi.boot.java.component.captcha.core.CaptchaChallenge;
import com.yomi.boot.java.component.captcha.core.CaptchaType;

import java.time.Instant;

/**
 * 验证码挑战响应（演示：响应模型由业务自行定义，组件只给 {@code CaptchaChallenge}）
 * <p>
 * {@code image} 为完整 data URI，前端可直接赋给 {@code <img src>}；想直接响应 {@code image/png}
 * 可用 {@code challenge.png()}。
 *
 * @param captchaId 挑战句柄（校验时回传）
 * @param type      挑战类型
 * @param image     图片 data URI
 * @param expireAt  过期时间
 * @author zwei
 */
public record TestCaptchaChallengeResult(String captchaId, CaptchaType type, String image, Instant expireAt) {

    /** 从挑战结果组装 DTO */
    public static TestCaptchaChallengeResult of(CaptchaChallenge challenge) {
        return new TestCaptchaChallengeResult(challenge.captchaId(), challenge.type(), challenge.dataUri(), challenge.expireAt());
    }

}
