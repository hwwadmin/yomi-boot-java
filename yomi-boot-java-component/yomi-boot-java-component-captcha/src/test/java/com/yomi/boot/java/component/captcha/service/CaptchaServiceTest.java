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
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void invalidateKeepsStoreErrorCode() {
        CaptchaStore codedFailure = new CaptchaStore() {
            @Override
            public void save(String captchaId, String answer) {
            }

            @Override
            public String remove(String captchaId) {
                throw CaptchaException.of(CaptchaErrorCode.VERIFY_FAILED, "存储层原始错误",
                        new IllegalStateException("redis down"));
            }

            @Override
            public String name() {
                return "coded";
            }
        };
        ICaptchaService service = new CaptchaService(Map.of(CaptchaType.TEXT, fixed(CaptchaType.TEXT, "AB12", "AB12")),
                renderer(), codedFailure, properties);
        CaptchaException error = assertThrows(CaptchaException.class, () -> service.invalidate("id"));
        assertEquals(CaptchaErrorCode.VERIFY_FAILED, error.getErrorCode(),
                "存储层已带错误码，作废不能再包一层改成 INVALIDATE_FAILED");
        assertInstanceOf(IllegalStateException.class, error.getCause(), "原始原因链必须保留（不再多套一层 CaptchaException）");
    }

    @Test
    void charsetWhoseUppercaseExpandsStaysVerifiable() {
        // ß 这类字符的 String.toUpperCase 是两字符（ß→SS）：答案被扩长后与图上所见失配，用户照着图输入也恒 MISMATCH
        CaptchaProperties.Text textProperties = new CaptchaProperties().getText();
        textProperties.setCharset("ßabcdefg");
        TextCaptchaGenerator delegate = new TextCaptchaGenerator(textProperties);
        AtomicReference<CaptchaContent> shown = new AtomicReference<>();
        CaptchaGenerator recorder = new CaptchaGenerator() {
            @Override
            public CaptchaType type() {
                return CaptchaType.TEXT;
            }

            @Override
            public CaptchaContent generate() {
                CaptchaContent content = delegate.generate();
                shown.set(content);
                return content;
            }
        };
        ICaptchaService service = new CaptchaService(Map.of(CaptchaType.TEXT, recorder), renderer(), store, properties);
        for (int i = 0; i < 200; i++) {
            CaptchaChallenge challenge = service.generate(CaptchaType.TEXT);
            CaptchaContent content = shown.get();
            if (content.displayText().contains("ß")) {
                assertEquals(CaptchaVerifyResult.OK, service.verify(challenge.captchaId(), content.displayText()),
                        "图上是 " + content.displayText() + "，照着图输入必须通过");
                return;
            }
        }
        fail("200 次生成都没抽到 ß，无法验证该场景");
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
