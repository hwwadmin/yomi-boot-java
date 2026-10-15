package com.yomi.boot.java.component.captcha.service;

import com.yomi.boot.java.component.captcha.ICaptchaService;
import com.yomi.boot.java.component.captcha.config.CaptchaProperties;
import com.yomi.boot.java.component.captcha.core.CaptchaChallenge;
import com.yomi.boot.java.component.captcha.core.CaptchaRandoms;
import com.yomi.boot.java.component.captcha.core.CaptchaType;
import com.yomi.boot.java.component.captcha.core.CaptchaVerifyResult;
import com.yomi.boot.java.component.captcha.exception.CaptchaErrorCode;
import com.yomi.boot.java.component.captcha.exception.CaptchaException;
import com.yomi.boot.java.component.captcha.generator.CaptchaContent;
import com.yomi.boot.java.component.captcha.generator.CaptchaGenerator;
import com.yomi.boot.java.component.captcha.render.CaptchaRenderer;
import com.yomi.boot.java.component.captcha.store.CaptchaStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * 默认验证码服务：生成 = 生成内容 → 渲染图片 → 存答案；校验 = 取出即删除后比对
 *
 * @author zwei
 */
@Slf4j
public class CaptchaService implements ICaptchaService {

    /** 各类型验证码生成器 */
    private final Map<CaptchaType, CaptchaGenerator> generators;

    /** PNG 图片渲染 */
    private final CaptchaRenderer renderer;

    /** 存储实现 */
    private final CaptchaStore store;

    /** 未指定类型时使用的默认验证码类型 */
    private final CaptchaType defaultType;

    /** 挑战有效期 */
    private final Duration ttl;

    /** 是否在日志中打印答案（仅调试用） */
    private final boolean logAnswer;

    public CaptchaService(Map<CaptchaType, CaptchaGenerator> generators, CaptchaRenderer renderer,
                                 CaptchaStore store, CaptchaProperties properties) {
        this.generators = Map.copyOf(generators);
        this.renderer = renderer;
        this.store = store;
        this.defaultType = properties.getDefaultType();
        this.ttl = properties.getTtl();
        this.logAnswer = properties.isLogAnswer();
    }

    /** 生成验证码挑战：渲染图片并将答案写入存储 */
    @Override
    public CaptchaChallenge generate(CaptchaType type) {
        CaptchaType actualType = type == null ? defaultType : type;
        CaptchaGenerator generator = generators.get(actualType);
        if (generator == null) {
            throw new IllegalArgumentException("不支持的验证码类型: " + actualType + "（已装配 " + generators.keySet() + "）");
        }
        String captchaId = CaptchaRandoms.nextId();
        try {
            CaptchaContent content = generator.generate();
            byte[] png = renderer.render(content.displayText(), CaptchaRandoms.nextSeed());
            store.save(captchaId, content.answer());
            if (logAnswer) {
                log.warn("[Captcha] 已生成 {} 挑战: id={}, 答案={}（yomi.captcha.log-answer=true，生产禁止开启）",
                        actualType, captchaId, content.answer());
            }
            return new CaptchaChallenge(captchaId, actualType, Base64.getEncoder().encodeToString(png),
                    Instant.now().plus(ttl));
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw CaptchaException.of(CaptchaErrorCode.GENERATE_FAILED, "验证码生成失败", e);
        }
    }

    /** 校验答案（取出即删，一次性消费） */
    @Override
    public CaptchaVerifyResult verify(String captchaId, String answer) {
        if (StringUtils.isBlank(captchaId) || StringUtils.isBlank(answer)) {
            return CaptchaVerifyResult.MISMATCH;
        }
        String expected;
        try {
            // 取出即删除：一次性消费，并发校验只有一个能拿到答案
            expected = store.remove(captchaId);
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw CaptchaException.of(CaptchaErrorCode.VERIFY_FAILED, "验证码校验失败", e);
        }
        if (expected == null) {
            return CaptchaVerifyResult.EXPIRED;
        }
        return expected.equalsIgnoreCase(answer.trim()) ? CaptchaVerifyResult.OK : CaptchaVerifyResult.MISMATCH;
    }

    /** 主动作废未使用的挑战 */
    @Override
    public void invalidate(String captchaId) {
        if (StringUtils.isBlank(captchaId)) {
            return;
        }
        try {
            store.remove(captchaId);
        } catch (CaptchaException e) {
            // 存储层已带错误码（宿主自定义 CaptchaStore），再包一层会掩盖原始错误码
            throw e;
        } catch (RuntimeException e) {
            throw CaptchaException.of(CaptchaErrorCode.INVALIDATE_FAILED, "验证码作废失败", e);
        }
    }

}
