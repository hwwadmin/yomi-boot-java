package com.yomi.boot.java.component.captcha.config;

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
