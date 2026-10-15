package com.yomi.boot.java.component.captcha.render;

import com.yomi.boot.java.component.captcha.config.CaptchaProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 内嵌字体契约
 */
class EmbeddedFontsTest {

    @Test
    void embeddedFontCoversRenderedCharset() {
        assertEquals("Roboto", EmbeddedFonts.font().getFamily());
        // 覆盖断言盯住"默认字符集 + 算术符号"：换字体时最容易丢掉的就是 ×（乘法算术题）
        String rendered = new CaptchaProperties().getText().getCharset() + "+-×=?";
        assertEquals(-1, EmbeddedFonts.font().canDisplayUpTo(rendered),
                "内嵌字体必须覆盖全部渲染字符（含 ×）");
    }

}
