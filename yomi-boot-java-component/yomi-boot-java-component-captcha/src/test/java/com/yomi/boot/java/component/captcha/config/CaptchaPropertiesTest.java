package com.yomi.boot.java.component.captcha.config;

import com.yomi.boot.java.component.captcha.store.CaptchaStoreType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置自检：默认值必须自洽，非法值启动期即报错（报错信息点名配置键）
 */
class CaptchaPropertiesTest {

    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> new CaptchaProperties().validate());
    }

    @Test
    void zeroTtlRejected() {
        CaptchaProperties properties = new CaptchaProperties();
        properties.setTtl(Duration.ZERO);
        assertTrue(assertThrows(IllegalStateException.class, properties::validate).getMessage().contains("yomi.captcha.ttl"));
    }

    @Test
    void tinyOrBlankCharsetRejected() {
        CaptchaProperties tiny = new CaptchaProperties();
        tiny.getText().setCharset("abc");
        assertTrue(assertThrows(IllegalStateException.class, tiny::validate).getMessage().contains("charset"));

        CaptchaProperties blank = new CaptchaProperties();
        blank.getText().setCharset(" ");
        assertTrue(assertThrows(IllegalStateException.class, blank::validate).getMessage().contains("charset"));
    }

    @Test
    void surrogatePairCharsetRejected() {
        CaptchaProperties properties = new CaptchaProperties();
        properties.getText().setCharset("😀😁😂🤣😃😄😅😆");
        String message = assertThrows(IllegalStateException.class, properties::validate).getMessage();
        assertTrue(message.contains("charset"), message);
    }

    @Test
    void invisibleCharsetRejected() {
        // 图上画不出、用户也打不出的字符（不间断空格、零宽空格）：挑战必然失败，绑定即拒绝
        for (String charset : new String[]{"abc\u00A0defg", "abc\u200Bdefg"}) {
            CaptchaProperties properties = new CaptchaProperties();
            properties.getText().setCharset(charset);
            String message = assertThrows(IllegalStateException.class, properties::validate).getMessage();
            assertTrue(message.contains("不可见"), message);
        }
    }

    @Test
    void unusedStorePrefixIsNotValidated() {
        // store=local 不落键、用不到前缀：前缀合法与否都不该阻断启动（校验在真正落键的 CaptchaKeys 里）
        CaptchaProperties properties = new CaptchaProperties();
        properties.setStore(CaptchaStoreType.LOCAL);
        properties.setStorePrefix("yomi:cap{tcha");
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void oversizedWaveAmplitudeRejected() {
        CaptchaProperties properties = new CaptchaProperties();
        properties.getFilter().getWave().setAmplitude(9);
        assertTrue(assertThrows(IllegalStateException.class, properties::validate).getMessage().contains("amplitude"));
    }

    @Test
    void unsupportedOperatorRejected() {
        CaptchaProperties properties = new CaptchaProperties();
        properties.getArithmetic().setOperators(List.of("*"));
        assertTrue(assertThrows(IllegalStateException.class, properties::validate).getMessage().contains("operators"));
    }

}
