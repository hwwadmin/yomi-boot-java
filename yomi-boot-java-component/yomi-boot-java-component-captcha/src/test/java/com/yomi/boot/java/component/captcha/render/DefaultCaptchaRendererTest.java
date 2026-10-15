package com.yomi.boot.java.component.captcha.render;

import com.yomi.boot.java.component.captcha.config.CaptchaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 渲染器：确定性、尺寸、滤镜生效、超出画布不裁切
 */
class DefaultCaptchaRendererTest {

    @Test
    void sameSeedProducesSameBytes() {
        DefaultCaptchaRenderer renderer = new DefaultCaptchaRenderer(properties());
        assertArrayEquals(renderer.render("K7QM", 42L), renderer.render("K7QM", 42L), "同文本 + 同种子必须字节一致");
        assertFalse(Arrays.equals(renderer.render("K7QM", 42L), renderer.render("K7QM", 43L)), "不同种子应渲染不同图片");
    }

    @Test
    void imageHasConfiguredSize() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(new DefaultCaptchaRenderer(properties()).render("A1B2", 1L)));
        assertEquals(150, image.getWidth());
        assertEquals(50, image.getHeight());
    }

    @Test
    void eachFilterActuallyChangesPixels() {
        byte[] plain = render(properties(), false, false, false);
        assertFalse(Arrays.equals(plain, render(properties(), true, false, false)), "干扰线应改变图片");
        assertFalse(Arrays.equals(plain, render(properties(), false, true, false)), "噪点应改变图片");
        assertFalse(Arrays.equals(plain, render(properties(), false, false, true)), "波纹应改变图片");
    }

    @Test
    void oversizedTextShrinksInsteadOfClipping() throws Exception {
        CaptchaProperties properties = properties();
        properties.getImage().setFontSize(40);
        // 40px 字号下 6 个字符超出可用宽度，必须缩字号；裁切会导致图上内容与答案不一致
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(new DefaultCaptchaRenderer(properties).render("12-7=?", 5L)));
        int[] bounds = inkBounds(image);
        assertTrue(bounds[2] - bounds[0] > image.getWidth() / 2, "应画出内容: " + Arrays.toString(bounds));
        assertTrue(bounds[0] >= 1 && bounds[2] <= image.getWidth() - 2,
                "内容不能溢出画布边缘（裁切）: " + Arrays.toString(bounds));
    }

    @Test
    void fontSizeAtUpperBoundKeepsDescendersInsideCanvas() throws Exception {
        CaptchaProperties properties = properties();
        properties.getImage().setFontSize(40);
        assertDoesNotThrow(properties::validate, "40px 是校验允许的上界，不该被拒");
        properties.getFilter().getLine().setEnabled(false);
        properties.getFilter().getNoise().setEnabled(false);
        properties.getFilter().getWave().setEnabled(false);
        // 带降部的字符（g/q/y）在 40px 字号下字形框约 47px（Roboto ≈ 1.17em）：
        // 基线若按字号估算会整体压低，降部被画到画布外
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(new DefaultCaptchaRenderer(properties).render("agqy", 5L)));
        int[] bounds = inkBounds(image);
        assertTrue(bounds[1] >= 1, "内容不能贴/出上缘: " + Arrays.toString(bounds));
        assertTrue(bounds[3] <= image.getHeight() - 2, "降部不能贴/出下缘（裁切）: " + Arrays.toString(bounds));
    }

    @Test
    void pngEncodingIgnoresImageIOCacheDirectory(@TempDir Path tempDir) throws Exception {
        // 把 ImageIO 的缓存目录指向一个随即删除的目录：走文件缓存的实现会因"目录不存在"直接失败
        // （即线上 java.io.tmpdir 不可写/不存在时每次 generate 都 500 的根因）
        File cacheDir = tempDir.resolve("imageio-cache").toFile();
        assertTrue(cacheDir.mkdir(), "创建缓存目录失败: " + cacheDir);
        File previousDirectory = ImageIO.getCacheDirectory();
        boolean previousUseCache = ImageIO.getUseCache();
        try {
            ImageIO.setCacheDirectory(cacheDir);
            ImageIO.setUseCache(true);
            assertTrue(cacheDir.delete(), "删除缓存目录失败: " + cacheDir);
            byte[] png = new DefaultCaptchaRenderer(properties()).render("A1B2", 3L);
            assertTrue(png.length > 0, "缓存目录不可用时仍应能编码出 PNG");
        } finally {
            ImageIO.setCacheDirectory(previousDirectory);
            ImageIO.setUseCache(previousUseCache);
        }
    }

    /** 按开关渲染一张图 */
    private static byte[] render(CaptchaProperties properties, boolean line, boolean noise, boolean wave) {
        properties.getFilter().getLine().setEnabled(line);
        properties.getFilter().getNoise().setEnabled(noise);
        properties.getFilter().getWave().setEnabled(wave);
        return new DefaultCaptchaRenderer(properties).render("A1B2", 7L);
    }

    /** 墨迹（深色像素）包围盒：[minX, minY, maxX, maxY] */
    private static int[] inkBounds(BufferedImage image) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int luminance = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
                if (luminance < 120) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    private static CaptchaProperties properties() {
        CaptchaProperties properties = new CaptchaProperties();
        properties.validate();
        return properties;
    }

}
