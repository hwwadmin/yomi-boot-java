package com.yomi.boot.java.component.captcha.render;

/**
 * 验证码图片渲染器（SPI）：宿主提供自己的 Bean 即可整体替换渲染效果
 *
 * @author zwei
 */
public interface CaptchaRenderer {

    /**
     * 渲染验证码图片
     *
     * @param text 图片上要展示的内容（算术题为算式）
     * @param seed 随机种子：同文本 + 同种子输出字节一致，便于测试复现
     * @return PNG 字节
     */
    byte[] render(String text, long seed);

}
