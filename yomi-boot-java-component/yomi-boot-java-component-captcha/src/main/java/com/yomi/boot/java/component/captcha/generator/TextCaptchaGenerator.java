package com.yomi.boot.java.component.captcha.generator;

import com.yomi.boot.java.component.captcha.config.CaptchaProperties;
import com.yomi.boot.java.component.captcha.core.CaptchaRandoms;
import com.yomi.boot.java.component.captcha.core.CaptchaType;
import lombok.RequiredArgsConstructor;

/**
 * 读图输入：从字符集随机取字，答案即展示文本（校验大小写不敏感，故答案取大写形式）
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class TextCaptchaGenerator implements CaptchaGenerator {

    private final CaptchaProperties.Text properties;

    /** {@link CaptchaType#TEXT} */
    @Override
    public CaptchaType type() {
        return CaptchaType.TEXT;
    }

    /** 从配置的字符集与长度随机拼串（按 code point 取字，不切断代理对），答案为大写形式供大小写不敏感校验 */
    @Override
    public CaptchaContent generate() {
        int[] candidates = properties.getCharset().codePoints().toArray();
        StringBuilder text = new StringBuilder(properties.getLength());
        for (int i = 0; i < properties.getLength(); i++) {
            text.appendCodePoint(candidates[CaptchaRandoms.nextInt(candidates.length)]);
        }
        String value = text.toString();
        // 逐 code point 大写：String.toUpperCase 会把 ß 这类字符扩成多字符（ß→SS），
        // 答案与图上所见长度不符，用户照着图输入会恒 MISMATCH
        StringBuilder answer = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> answer.appendCodePoint(Character.toUpperCase(codePoint)));
        return new CaptchaContent(value, answer.toString());
    }

}
