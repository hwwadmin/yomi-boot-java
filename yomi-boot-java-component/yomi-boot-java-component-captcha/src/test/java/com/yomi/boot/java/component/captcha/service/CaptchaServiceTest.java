package com.yomi.boot.java.component.captcha.service;

import com.yomi.boot.java.component.captcha.ICaptchaService;
import com.yomi.boot.java.component.captcha.MutableTicker;
import com.yomi.boot.java.component.captcha.config.CaptchaProperties;
import com.yomi.boot.java.component.captcha.core.CaptchaChallenge;
import com.yomi.boot.java.component.captcha.core.CaptchaType;
import com.yomi.boot.java.component.captcha.core.CaptchaVerifyResult;
import com.yomi.boot.java.component.captcha.exception.CaptchaErrorCode;
import com.yomi.boot.java.component.captcha.exception.CaptchaException;
import com.yomi.boot.java.component.captcha.generator.ArithmeticCaptchaGenerator;
import com.yomi.boot.java.component.captcha.generator.CaptchaContent;
import com.yomi.boot.java.component.captcha.generator.CaptchaGenerator;
import com.yomi.boot.java.component.captcha.generator.TextCaptchaGenerator;
import com.yomi.boot.java.component.captcha.render.CaptchaRenderer;
import com.yomi.boot.java.component.captcha.render.DefaultCaptchaRenderer;
import com.yomi.boot.java.component.captcha.store.CaptchaStore;
import com.yomi.boot.java.component.captcha.store.LocalCaptchaStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务层契约：一次性消费、大小写不敏感、过期、诚实失败（基础设施异常不伪装成输错）
 */
class CaptchaServiceTest {

    private static final Duration TTL = Duration.ofSeconds(10);

    private final MutableTicker ticker = new MutableTicker();

    private final LocalCaptchaStore store = new LocalCaptchaStore(TTL, 100, ticker);

    private final CaptchaProperties properties = properties();

    @Test
    void generateWithRealStackProducesPng() {
        Map<CaptchaType, CaptchaGenerator> generators = Map.of(
                CaptchaType.TEXT, new TextCaptchaGenerator(properties.getText()),
                CaptchaType.ARITHMETIC, new ArithmeticCaptchaGenerator(properties.getArithmetic()));
        ICaptchaService service = new CaptchaService(generators, renderer(), store, properties);
        for (CaptchaType type : CaptchaType.values()) {
            CaptchaChallenge challenge = service.generate(type);
            assertEquals(type, challenge.type());
            assertFalse(challenge.captchaId().isBlank());
            assertTrue(challenge.expireAt().isAfter(java.time.Instant.now()));
            assertTrue(challenge.dataUri().startsWith("data:image/png;base64,"));
            assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, Arrays.copyOf(challenge.png(), 4), "应为 PNG 图片");
        }
    }

    @Test
    void nullTypeUsesDefaultType() {
        assertEquals(CaptchaType.TEXT, fixedService("AB12").generate(null).type());
    }

    @Test
    void verifyConsumesOnSuccess() {
        ICaptchaService service = fixedService("AB12");
        CaptchaChallenge challenge = service.generate(CaptchaType.TEXT);
        assertEquals(CaptchaVerifyResult.OK, service.verify(challenge.captchaId(), "AB12"));
        assertEquals(CaptchaVerifyResult.EXPIRED, service.verify(challenge.captchaId(), "AB12"), "成功后挑战即消费");
    }

    @Test
    void verifyIsCaseInsensitiveAndTrimmed() {
        ICaptchaService service = fixedService("AB12");
        CaptchaChallenge challenge = service.generate(CaptchaType.TEXT);
        assertEquals(CaptchaVerifyResult.OK, service.verify(challenge.captchaId(), " ab12 "));
    }

    @Test
    void verifyConsumesOnMismatch() {
        ICaptchaService service = fixedService("AB12");
        CaptchaChallenge challenge = service.generate(CaptchaType.TEXT);
        assertEquals(CaptchaVerifyResult.MISMATCH, service.verify(challenge.captchaId(), "ZZZZ"));
        assertEquals(CaptchaVerifyResult.EXPIRED, service.verify(challenge.captchaId(), "AB12"), "输错也消费（防爆破）");
    }

    @Test
    void verifyExpiredChallenge() {
        ICaptchaService service = fixedService("AB12");
        CaptchaChallenge challenge = service.generate(CaptchaType.TEXT);
        ticker.advance(TTL.plusSeconds(1));
        assertEquals(CaptchaVerifyResult.EXPIRED, service.verify(challenge.captchaId(), "AB12"));
    }

    @Test
    void verifyWithBlankInputsIsMismatch() {
        ICaptchaService service = fixedService("AB12");
        assertEquals(CaptchaVerifyResult.MISMATCH, service.verify(null, "AB12"));
        assertEquals(CaptchaVerifyResult.MISMATCH, service.verify("id", "   "));
    }

    @Test
    void invalidateMakesChallengeExpired() {
        ICaptchaService service = fixedService("AB12");
        CaptchaChallenge challenge = service.generate(CaptchaType.TEXT);
        service.invalidate(challenge.captchaId());
        assertEquals(CaptchaVerifyResult.EXPIRED, service.verify(challenge.captchaId(), "AB12"));
    }

    @Test
    void unsupportedTypeIsRejected() {
        ICaptchaService service = new CaptchaService(Map.of(CaptchaType.TEXT, fixed(CaptchaType.TEXT, "AB12", "AB12")),
                renderer(), store, properties);
        assertThrows(IllegalArgumentException.class, () -> service.generate(CaptchaType.ARITHMETIC));
    }

    @Test
    void storeFailureFailsHonestly() {
        CaptchaStore failing = new CaptchaStore() {
            @Override
            public void save(String captchaId, String answer) {
                throw new IllegalStateException("存储不可用");
            }

            @Override
            public String remove(String captchaId) {
                throw new IllegalStateException("存储不可用");
            }

            @Override
            public String name() {
                return "failing";
            }
        };
        ICaptchaService service = new CaptchaService(Map.of(CaptchaType.TEXT, fixed(CaptchaType.TEXT, "AB12", "AB12")),
                renderer(), failing, properties);
        CaptchaException generateError = assertThrows(CaptchaException.class, () -> service.generate(CaptchaType.TEXT));
        assertEquals(CaptchaErrorCode.GENERATE_FAILED, generateError.getErrorCode());
        assertEquals(500, generateError.getCode());
        CaptchaException verifyError = assertThrows(CaptchaException.class, () -> service.verify("id", "AB12"));
        assertEquals(CaptchaErrorCode.VERIFY_FAILED, verifyError.getErrorCode());
        assertEquals(500, verifyError.getCode());
        CaptchaException invalidateError = assertThrows(CaptchaException.class, () -> service.invalidate("id"));
        assertEquals(CaptchaErrorCode.INVALIDATE_FAILED, invalidateError.getErrorCode());
        assertEquals(500, invalidateError.getCode());
    }

    /** 固定答案的服务：让契约测试不依赖随机字符 */
    private ICaptchaService fixedService(String answer) {
        Map<CaptchaType, CaptchaGenerator> generators = Map.of(
                CaptchaType.TEXT, fixed(CaptchaType.TEXT, answer, answer),
                CaptchaType.ARITHMETIC, fixed(CaptchaType.ARITHMETIC, "3×5=?", answer));
        return new CaptchaService(generators, renderer(), store, properties);
    }

    private CaptchaRenderer renderer() {
        return new DefaultCaptchaRenderer(properties);
    }

    private static CaptchaGenerator fixed(CaptchaType type, String displayText, String answer) {
        return new CaptchaGenerator() {
            @Override
            public CaptchaType type() {
                return type;
            }

            @Override
            public CaptchaContent generate() {
                return new CaptchaContent(displayText, answer);
            }
        };
    }

    private static CaptchaProperties properties() {
        CaptchaProperties properties = new CaptchaProperties();
        properties.validate();
        return properties;
    }

}
