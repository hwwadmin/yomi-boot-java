package com.yomi.boot.java.component.captcha.render;

import com.yomi.boot.java.component.captcha.config.CaptchaProperties;
import com.yomi.boot.java.component.captcha.exception.CaptchaErrorCode;
import com.yomi.boot.java.component.captcha.exception.CaptchaException;
import com.yomi.boot.java.component.captcha.render.filter.CaptchaFilter;
import com.yomi.boot.java.component.captcha.render.filter.LineFilter;
import com.yomi.boot.java.component.captcha.render.filter.NoiseFilter;
import com.yomi.boot.java.component.captcha.render.filter.WaveFilter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

/**
 * 默认渲染器：背景渐变 → 干扰线 → 逐字绘制 → 噪点 → 自定义滤镜 → 波纹
 * <p>
 * 同文本 + 同种子输出字节一致；字符总宽或字形框高超出画布时按字体度量等比缩小字号而非裁切——
 * 裁切会让图上内容与答案不一致，是最难排查的静默故障。
 *
 * @author zwei
 */
@Slf4j
public class DefaultCaptchaRenderer implements CaptchaRenderer {

    /** 内嵌字体标识 */
    private static final String EMBEDDED = "embedded";

    /** 字符与画布边缘的最小留白（像素） */
    private static final int MARGIN = 8;

    /** 字符间距（像素） */
    private static final int CHAR_GAP = 1;

    /** 字号抖动上限（相对配置字号的比例） */
    private static final double FONT_SIZE_JITTER_RATIO = 1.0 / 14;

    /** 模块配置 */
    private final CaptchaProperties properties;

    /** 绘制用基准字体（内嵌或系统族，已解析回退） */
    private final Font baseFont;

    /** 干扰线（字符下层），未启用为 null */
    private final CaptchaFilter lineFilter;

    /** 噪点（字符上层），未启用为 null */
    private final CaptchaFilter noiseFilter;

    /** 波纹（最后施加），未启用为 null */
    private final CaptchaFilter waveFilter;

    /** 宿主自定义滤镜：字符之后、波纹之前叠加 */
    private final List<CaptchaFilter> overlayFilters;

    public DefaultCaptchaRenderer(CaptchaProperties properties) {
        this(properties, List.of());
    }

    public DefaultCaptchaRenderer(CaptchaProperties properties, List<CaptchaFilter> overlayFilters) {
        this.properties = properties;
        this.baseFont = resolveFont(properties.getImage().getFontFamily());
        warnUncoveredCharset(properties, baseFont);
        CaptchaProperties.Filter filter = properties.getFilter();
        this.lineFilter = filter.getLine().isEnabled() ? new LineFilter(filter.getLine().getCount()) : null;
        this.noiseFilter = filter.getNoise().isEnabled() ? new NoiseFilter(filter.getNoise().getDots()) : null;
        this.waveFilter = filter.getWave().isEnabled()
                ? new WaveFilter(filter.getWave().getAmplitude(), filter.getWave().getWavelength())
                : null;
        this.overlayFilters = List.copyOf(overlayFilters);
        // 预热：把字体与绘图类初始化成本（首张约 90ms）移出用户请求路径
        render("A1B2", 0L);
        log.info("[Captcha] 渲染器已就绪: 字体={}, 画布={}x{}, 字号={}, 干扰线={}, 噪点={}, 波纹={}, 自定义滤镜={}",
                baseFont.getFamily(), properties.getImage().getWidth(), properties.getImage().getHeight(),
                properties.getImage().getFontSize(),
                lineFilter == null ? "关" : filter.getLine().getCount() + " 条",
                noiseFilter == null ? "关" : filter.getNoise().getDots() + " 点",
                waveFilter == null ? "关" : filter.getWave().getAmplitude() + "/" + filter.getWave().getWavelength(),
                overlayFilters.size());
    }

    /** 固定种子下输出 PNG 字节，图层顺序见类说明 */
    @Override
    public byte[] render(String text, long seed) {
        CaptchaProperties.Image image = properties.getImage();
        Random random = new Random(seed);
        BufferedImage canvas = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        paintBackground(canvas);
        if (lineFilter != null) {
            lineFilter.apply(canvas, random);
        }
        drawText(canvas, text, new Random(seed * 31L));
        if (noiseFilter != null) {
            noiseFilter.apply(canvas, random);
        }
        overlayFilters.forEach(filter -> filter.apply(canvas, random));
        if (waveFilter != null) {
            waveFilter.apply(canvas, random);
        }
        return toPng(canvas);
    }

    /** 浅色渐变背景 */
    private static void paintBackground(BufferedImage canvas) {
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        Graphics2D g = canvas.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(252, 252, 254), width, height, new Color(230, 234, 242)));
        g.fillRect(0, 0, width, height);
        g.dispose();
    }

    /** 逐字绘制：按 FontMetrics 布局 + 随机旋转/抖动/字号浮动 */
    private void drawText(BufferedImage canvas, String text, Random random) {
        CaptchaProperties.Image image = properties.getImage();
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int fontSize = image.getFontSize();
        Font font = baseFont.deriveFont((float) fontSize);
        FontMetrics metrics = g.getFontMetrics(font);
        // 超出画布不裁切：等比缩小字号，保证图上内容与答案一致
        double scale = 1.0;
        int totalWidth = textWidth(text, metrics);
        if (totalWidth > canvas.getWidth() - 2 * MARGIN) {
            scale = (double) (canvas.getWidth() - 2 * MARGIN) / totalWidth;
        }
        // 纵向同样按字体度量贴合：字形框（ascent+descent）加基线抖动与字号抖动后仍须落在画布内，
        // 否则带降部（g/q/y）的字符会被画到画布外（字体框远高于字号本身：Roboto ≈ 1.17em）
        double glyphHeight = metrics.getAscent() + metrics.getDescent();
        double heightBudget = canvas.getHeight() - 2 * image.getBaselineJitter();
        scale = Math.min(scale, heightBudget / (glyphHeight * (1 + FONT_SIZE_JITTER_RATIO)));
        if (scale < 1.0) {
            fontSize = Math.max(12, (int) Math.floor(fontSize * scale));
            font = baseFont.deriveFont((float) fontSize);
            metrics = g.getFontMetrics(font);
            totalWidth = textWidth(text, metrics);
            glyphHeight = metrics.getAscent() + metrics.getDescent();
        }
        int x = Math.max(MARGIN, (canvas.getWidth() - totalWidth) / 2);
        // 基线按 ascent/descent 居中，而不是按字号估算（字号 ≈ 1.17×字形框，估算会把字压到画布下缘）
        int baseline = (int) Math.round((canvas.getHeight() - glyphHeight) / 2 + metrics.getAscent());
        int sizeJitter = Math.max(1, (int) (fontSize * FONT_SIZE_JITTER_RATIO));
        for (int i = 0; i < text.length(); i++) {
            char glyphText = text.charAt(i);
            Graphics2D glyph = (Graphics2D) g.create();
            int glyphX = x;
            int glyphY = baseline + jitter(random, image.getBaselineJitter());
            glyph.rotate(rotate(random, image.getCharRotate()), glyphX, glyphY);
            glyph.setColor(glyphColor(random));
            glyph.setFont(font.deriveFont((float) (fontSize + jitter(random, sizeJitter))));
            glyph.drawString(String.valueOf(glyphText), glyphX, glyphY);
            glyph.dispose();
            x += metrics.charWidth(glyphText) + CHAR_GAP;
        }
        g.dispose();
    }

    /** 文本总宽（含字间距） */
    private static int textWidth(String text, FontMetrics metrics) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += metrics.charWidth(text.charAt(i));
        }
        return width + (text.length() - 1) * CHAR_GAP;
    }

    /** 返回 [-bound, bound] 的整型抖动 */
    private static int jitter(Random random, int bound) {
        return bound <= 0 ? 0 : random.nextInt(bound * 2 + 1) - bound;
    }

    /** 返回 [-bound, bound] 的弧度旋转 */
    private static double rotate(Random random, double bound) {
        return bound <= 0 ? 0 : (random.nextDouble() * 2 - 1) * bound;
    }

    /** 深色系随机字色 */
    private static Color glyphColor(Random random) {
        return new Color(10 + random.nextInt(70), 10 + random.nextInt(70), 100 + random.nextInt(110));
    }

    /** embedded（或未配置）用内嵌字体；系统字体族不存在时回退内嵌并告警 */
    private static Font resolveFont(String fontFamily) {
        if (StringUtils.isBlank(fontFamily) || EMBEDDED.equalsIgnoreCase(fontFamily)) {
            return EmbeddedFonts.font();
        }
        Font font = new Font(fontFamily, Font.PLAIN, 12);
        if (!font.getFamily().equalsIgnoreCase(fontFamily) && !font.getName().equalsIgnoreCase(fontFamily)) {
            log.warn("[Captcha] 系统字体 {} 不存在，回退内嵌字体（跨环境渲染一致性以内嵌字体为基准）", fontFamily);
            return EmbeddedFonts.font();
        }
        return font;
    }

    /** 字符集含字体未覆盖字形时启动期告警：图上会画成缺字方块，人无法辨识、校验恒 MISMATCH */
    private static void warnUncoveredCharset(CaptchaProperties properties, Font font) {
        CaptchaProperties.Text text = properties.getText();
        if (text == null || StringUtils.isBlank(text.getCharset())) {
            return;
        }
        String uncovered = text.getCharset().chars().filter(c -> !font.canDisplay(c)).distinct()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        if (!uncovered.isEmpty()) {
            log.warn("[Captcha] yomi.captcha.text.charset 含字体 {} 未覆盖的字符 [{}]，图上会画成缺字方块；"
                    + "请改用字体覆盖的字符集（默认字符集即满足）", font.getFamily(), uncovered);
        }
    }

    /** PNG 编码：显式走内存缓存流，ImageIO 默认的文件缓存要求 java.io.tmpdir 可写，容器只读根文件系统下会整块失败 */
    private static byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream imageOut = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, "png", imageOut)) {
                throw CaptchaException.of(CaptchaErrorCode.GENERATE_FAILED, "验证码图片编码失败：运行时无 PNG 编码器", null);
            }
            imageOut.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw CaptchaException.of(CaptchaErrorCode.GENERATE_FAILED, "验证码图片编码失败", e);
        }
    }

}
