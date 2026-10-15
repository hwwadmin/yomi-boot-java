package com.yomi.boot.java.component.captcha.render.filter;

import lombok.RequiredArgsConstructor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.CubicCurve2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 干扰线：在字符下层画随机贝塞尔曲线
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class LineFilter implements CaptchaFilter {

    /** 贝塞尔干扰线条数（对应 {@code yomi.captcha.filter.line.count}） */
    private final int count;

    /** 在画布上绘制 {@link #count} 条随机三次贝塞尔曲线 */
    @Override
    public void apply(BufferedImage image, Random random) {
        int width = image.getWidth();
        int height = image.getHeight();
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < count; i++) {
            g.setColor(new Color(150 + random.nextInt(80), 150 + random.nextInt(80), 150 + random.nextInt(80)));
            g.draw(new CubicCurve2D.Double(random.nextInt(width), random.nextInt(height),
                    random.nextInt(width), random.nextInt(height),
                    random.nextInt(width), random.nextInt(height),
                    random.nextInt(width), random.nextInt(height)));
        }
        g.dispose();
    }

}
