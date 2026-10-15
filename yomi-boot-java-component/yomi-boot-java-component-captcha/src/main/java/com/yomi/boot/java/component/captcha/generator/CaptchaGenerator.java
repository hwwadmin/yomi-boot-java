package com.yomi.boot.java.component.captcha.generator;

import com.yomi.boot.java.component.captcha.core.CaptchaType;

/**
 * 挑战内容生成器（SPI）：宿主可实现自己的生成器 Bean 扩展类型
 *
 * @author zwei
 */
public interface CaptchaGenerator {

    /**
     * 支持的挑战类型
     */
    CaptchaType type();

    /**
     * 生成一次挑战内容
     */
    CaptchaContent generate();

}
