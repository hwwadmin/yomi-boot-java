package com.yomi.boot.java.qucik.starter.testcaptcha.service;

import com.yomi.boot.java.component.captcha.ICaptchaService;
import com.yomi.boot.java.component.captcha.core.CaptchaChallenge;
import com.yomi.boot.java.component.captcha.core.CaptchaType;
import com.yomi.boot.java.component.captcha.core.CaptchaVerifyResult;
import com.yomi.boot.java.qucik.starter.testcaptcha.dto.TestCaptchaChallengeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 验证码流程测试（演示业务侧如何消费组件能力）
 * <p>
 * 登录等业务动作应在同一请求内调用 {@link ICaptchaService#verify}：MISMATCH/EXPIRED 是正常业务结果，
 * 基础设施故障抛 {@code CaptchaException}（组件 advice 输出 500 + 错误码）。
 *
 * @author zwei
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.captcha.enabled", havingValue = "true")
public class TestCaptchaFlowService {

    /** 验证码组件服务 */
    private final ICaptchaService captchaService;

    /**
     * 生成挑战（type 为 null 时用配置的默认类型）
     */
    public TestCaptchaChallengeResult generate(CaptchaType type) {
        CaptchaChallenge challenge = captchaService.generate(type);
        log.info("[Captcha测试] 生成挑战: id={}, type={}, expireAt={}",
                challenge.captchaId(), challenge.type(), challenge.expireAt());
        return TestCaptchaChallengeResult.of(challenge);
    }

    /**
     * 校验答案（一次性消费：无论输对输错，挑战即刻失效）
     */
    public CaptchaVerifyResult verify(String captchaId, String answer) {
        CaptchaVerifyResult result = captchaService.verify(captchaId, answer);
        log.info("[Captcha测试] 校验挑战: id={}, result={}", captchaId, result);
        return result;
    }

}
