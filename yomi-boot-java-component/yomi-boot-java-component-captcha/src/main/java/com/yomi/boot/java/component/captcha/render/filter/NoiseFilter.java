package com.yomi.boot.java.component.captcha.render.filter;

import lombok.RequiredArgsConstructor;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 噪点：在字符上层撒 1 像素彩点（颜色避开纯白纯黑，减少抄图工具的对比度优势）
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class NoiseFilter implements CaptchaFilter {

    /** 噪点个数（对应 {@code yomi.captcha.filter.noise.dots}） */
    private final int dots;

    /** 在随机坐标撒 {@link #dots} 个 1 像素彩点 */
    @Override
    public void apply(BufferedImage image, Random random) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int i = 0; i < dots; i++) {
            Color color = new Color(90 + random.nextInt(140), 90 + random.nextInt(140), 90 + random.nextInt(140));
            image.setRGB(random.nextInt(width), random.nextInt(height), color.getRGB());
        }
    }

}
