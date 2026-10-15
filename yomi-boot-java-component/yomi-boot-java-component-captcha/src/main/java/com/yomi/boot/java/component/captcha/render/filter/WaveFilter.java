package com.yomi.boot.java.component.captcha.render.filter;

import lombok.RequiredArgsConstructor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 波纹：按行水平位移 {@code x' = x + A·sin(2πy/λ)}
 * <p>
 * 幅度实测阈值：150×50 画布、28px 字号下 A≤2 可辨认、A=3 开始破字，默认 1.5。
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class WaveFilter implements CaptchaFilter {

    /** 幅度（像素） */
    private final double amplitude;

    /** 波长（像素） */
    private final double wavelength;

    /** 按正弦位移逐行重采样像素，实现水平波纹 */
    @Override
    public void apply(BufferedImage image, Random random) {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        for (int y = 0; y < height; y++) {
            int offset = (int) Math.round(amplitude * Math.sin(2 * Math.PI * y / wavelength));
            if (offset == 0) {
                continue;
            }
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, source.getRGB(Math.min(width - 1, Math.max(0, x + offset)), y));
            }
        }
    }

}
