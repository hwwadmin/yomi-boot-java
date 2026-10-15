package com.yomi.boot.java.component.captcha.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 类型解析：接口入参口径与 yml 一致（小写可识别）
 */
class CaptchaTypeTest {

    @Test
    void parsesLowercaseAndUppercase() {
        assertEquals(CaptchaType.TEXT, CaptchaType.parse("text", null));
        assertEquals(CaptchaType.TEXT, CaptchaType.parse("TEXT", null));
        assertEquals(CaptchaType.ARITHMETIC, CaptchaType.parse(" arithmetic ", null));
    }

    @Test
    void blankOrUnknownFallsBack() {
        assertNull(CaptchaType.parse(null, null));
        assertNull(CaptchaType.parse("  ", null));
        assertEquals(CaptchaType.TEXT, CaptchaType.parse("unknown", CaptchaType.TEXT));
    }

}
