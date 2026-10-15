package com.yomi.boot.java.component.captcha.generator;

import com.yomi.boot.java.component.captcha.config.CaptchaProperties;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 读图输入生成器：长度、字符集、答案归一
 */
class TextCaptchaGeneratorTest {

    @Test
    void generatesConfiguredLengthWithinCharset() {
        CaptchaProperties.Text properties = new CaptchaProperties().getText();
        TextCaptchaGenerator generator = new TextCaptchaGenerator(properties);
        for (int i = 0; i < 50; i++) {
            CaptchaContent content = generator.generate();
            assertEquals(properties.getLength(), content.displayText().length());
            assertEquals(content.displayText().toUpperCase(Locale.ROOT), content.answer());
            for (char c : content.displayText().toCharArray()) {
                assertTrue(properties.getCharset().indexOf(c) >= 0, "字符 " + c + " 不在字符集内");
            }
        }
    }

    @Test
    void defaultCharsetExcludesAmbiguousChars() {
        String charset = new CaptchaProperties().getText().getCharset();
        for (char ambiguous : "0O1IlL2Z5S".toCharArray()) {
            assertFalse(charset.indexOf(ambiguous) >= 0, "默认字符集不应含易混字符 " + ambiguous);
        }
    }

    @Test
    void answerIsUppercaseForLowercaseCharset() {
        CaptchaProperties.Text properties = new CaptchaProperties().getText();
        properties.setCharset("abcdefgh");
        CaptchaContent content = new TextCaptchaGenerator(properties).generate();
        assertEquals(content.displayText().toUpperCase(Locale.ROOT), content.answer());
    }

    @Test
    void answerNeverExpandsForSpecialCaseChars() {
        // ß 的 String.toUpperCase 是两字符（SS）：答案一旦被扩长就与图上字符失配，用户照着图输入恒不符
        CaptchaProperties.Text properties = new CaptchaProperties().getText();
        properties.setCharset("ßabcdefg");
        TextCaptchaGenerator generator = new TextCaptchaGenerator(properties);
        for (int i = 0; i < 50; i++) {
            CaptchaContent content = generator.generate();
            assertEquals(content.displayText().length(), content.answer().length(), "答案必须与图上字符等长");
            assertTrue(content.displayText().equalsIgnoreCase(content.answer()),
                    "答案与图上字符必须大小写不敏感相等: " + content.displayText() + " / " + content.answer());
        }
    }

    @Test
    void supplementaryCharsetPicksWholeCodePoints() {
        // 配置期已拒绝代理对，这里绕过校验直接构造生成器：取字本身也必须按 code point（不产生孤立代理项）
        CaptchaProperties.Text properties = new CaptchaProperties().getText();
        properties.setCharset("😀😁😂🤣😃😄😅😆");
        TextCaptchaGenerator generator = new TextCaptchaGenerator(properties);
        for (int i = 0; i < 50; i++) {
            CaptchaContent content = generator.generate();
            int[] codePoints = content.displayText().codePoints().toArray();
            assertEquals(properties.getLength(), codePoints.length, "字符数按 code point 计");
            for (int codePoint : codePoints) {
                assertTrue(Character.isSupplementaryCodePoint(codePoint)
                                && properties.getCharset().indexOf(codePoint) >= 0,
                        "取字必须是字符集内的完整字符: " + content.displayText());
            }
        }
    }

}
