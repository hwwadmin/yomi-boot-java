package com.yomi.boot.java.component.captcha.render.filter;

import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 干扰滤镜（SPI）：就地修改图片
 *
 * @author zwei
 */
public interface CaptchaFilter {

    /**
     * 施加干扰
     *
     * @param image  待处理图片（就地修改）
     * @param random 渲染随机源（同一种子可复现）
     */
    void apply(BufferedImage image, Random random);

}
