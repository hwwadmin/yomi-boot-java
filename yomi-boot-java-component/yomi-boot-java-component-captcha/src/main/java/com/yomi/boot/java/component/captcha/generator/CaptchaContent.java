package com.yomi.boot.java.component.captcha.generator;

/**
 * 生成结果：{@code displayText} 是图片上展示的内容，{@code answer} 是校验答案（两者可能不同，如算术题）
 *
 * @author zwei
 */
public record CaptchaContent(String displayText, String answer) {
}
