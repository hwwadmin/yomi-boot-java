package com.yomi.boot.java.component.captcha.config;

import com.yomi.boot.java.component.captcha.core.CaptchaType;
import com.yomi.boot.java.component.captcha.store.CaptchaStoreType;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 验证码组件配置，对应 yml 前缀 yomi.captcha
 * <p>
 * 由 {@link CaptchaAutoConfiguration} 在 {@code yomi.captcha.enabled=true} 时绑定；
 * 绑定完成即自检（{@link #validate()}，装配期校验）；未启用时不注册，配置错误不阻断启动。
 *
 * @author zwei
 */
@Data
@ConfigurationProperties(prefix = "yomi.captcha")
public class CaptchaProperties {

    /** 是否启用验证码组件，未配置默认 false */
    private boolean enabled = false;

    /** 存储实现：auto（配了 Redis 用 redis，否则 local）/ local / redis */
    private CaptchaStoreType store = CaptchaStoreType.AUTO;

    /** 挑战键前缀，未配置时按 {@code yomi:captcha:{spring.application.name}} 补齐，多应用共用 Redis 时需保证唯一 */
    private String storePrefix;

    /** 挑战有效期（校验时超过该时长返回 EXPIRED） */
    private Duration ttl = Duration.ofMinutes(2);

    /** 生成未指定类型时的默认挑战类型 */
    private CaptchaType defaultType = CaptchaType.TEXT;

    /** 是否把答案打进日志（仅联调使用） */
    private boolean logAnswer = false;

    /** 图片参数 */
    private Image image = new Image();

    /** 读图输入参数 */
    private Text text = new Text();

    /** 看图算数参数 */
    private Arithmetic arithmetic = new Arithmetic();

    /** 干扰滤镜参数 */
    private Filter filter = new Filter();

    /** 本地存储参数 */
    private Local local = new Local();

    /**
     * 绑定完成即自检：组件启用就执行，与其它 Bean 的装配结果无关
     */
    @PostConstruct
    void init() {
        validate();
    }

    /**
     * 配置自检（装配期调用）
     * <p>
     * 只校验本类各参数的自洽性；{@code store-prefix} 的合法性由真正落键的 {@code CaptchaKeys} 校验
     * （store=local 不落键，前缀配置只影响 redis 存储）。
     */
    public void validate() {
        requirePositive(ttl, "yomi.captcha.ttl");
        if (store == null) {
            throw new IllegalStateException("yomi.captcha.store 取值 auto / local / redis");
        }
        if (defaultType == null) {
            throw new IllegalStateException("yomi.captcha.default-type 取值 text / arithmetic");
        }
        image.validate();
        text.validate();
        arithmetic.validate();
        filter.validate(image);
        local.validate();
    }

    /** 图片参数 */
    @Data
    public static class Image {

        /** 图片宽度（像素） */
        private int width = 150;

        /** 图片高度（像素） */
        private int height = 50;

        /** 字体：embedded 用内嵌 Roboto-Bold（跨环境一致、不依赖系统字体）；也可填系统字体族名 */
        private String fontFamily = "embedded";

        /** 字号（像素） */
        private int fontSize = 28;

        /** 单字符最大旋转角度（弧度，正负对称） */
        private double charRotate = 0.25;

        /** 基线上下抖动（像素，正负对称） */
        private int baselineJitter = 2;

        void validate() {
            requireRange(width, 80, 800, "yomi.captcha.image.width");
            requireRange(height, 30, 300, "yomi.captcha.image.height");
            requireRange(fontSize, 12, 120, "yomi.captcha.image.font-size");
            if (fontSize > height * 0.8) {
                throw new IllegalStateException("yomi.captcha.image.font-size 不能超过图片高度的 80%");
            }
            if (charRotate < 0 || charRotate > 0.6) {
                throw new IllegalStateException("yomi.captcha.image.char-rotate 取值 0 ~ 0.6（弧度）");
            }
            if (baselineJitter < 0 || baselineJitter > height / 4) {
                throw new IllegalStateException("yomi.captcha.image.baseline-jitter 不能超过图片高度的 1/4");
            }
        }
    }

    /** 读图输入参数 */
    @Data
    public static class Text {

        /** 字符个数 */
        private int length = 4;

        /** 字符集：默认剔除易混字符 0/O、1/I/L、2/Z、5/S */
        private String charset = "ABCDEFGHJKMNPQRTUVWXY34678";

        void validate() {
            requireRange(length, 3, 6, "yomi.captcha.text.length");
            if (StringUtils.isBlank(charset)) {
                throw new IllegalStateException("yomi.captcha.text.charset 不能为空");
            }
            // 不可见字符（空白/控制/格式类，如不间断空格、零宽空格、BOM）图上画不出、用户也打不出，挑战必然失败
            int invisible = charset.codePoints()
                    .filter(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)
                            || Character.getType(c) == Character.FORMAT)
                    .findFirst().orElse(-1);
            if (invisible >= 0) {
                throw new IllegalStateException("yomi.captcha.text.charset 不能包含不可见字符（空白/控制/格式类），当前含 U+"
                        + String.format("%04X", invisible));
            }
            // 图片按 UTF-16 单字符绘制，增补平面字符（代理对）画不全、答案也拼不出来，启动期直接拒绝
            if (charset.chars().anyMatch(c -> Character.isSurrogate((char) c))) {
                throw new IllegalStateException("yomi.captcha.text.charset 不能包含增补平面字符（代理对，如 emoji）："
                        + "图上会画成缺字方块，用户也无法输入该字符");
            }
            long distinct = charset.codePoints().distinct().count();
            if (distinct < 8) {
                throw new IllegalStateException("yomi.captcha.text.charset 去重后至少 8 个字符（当前 " + distinct + " 个），否则答案空间过小");
            }
        }
    }

    /** 看图算数参数 */
    @Data
    public static class Arithmetic {

        /** 支持的运算符：+ / - / × */
        public static final Set<String> SUPPORTED_OPERATORS = Set.of("+", "-", "×");

        /** 运算符集合 */
        private List<String> operators = new ArrayList<>(List.of("+", "-", "×"));

        /** 操作数最小值（含） */
        private int operandMin = 1;

        /** 操作数最大值（含） */
        private int operandMax = 9;

        /** 结果上限（含）；减法为负的组合与结果 0/1 都会被过滤 */
        private int maxResult = 20;

        void validate() {
            if (operators == null || operators.isEmpty()) {
                throw new IllegalStateException("yomi.captcha.arithmetic.operators 不能为空（取值 " + SUPPORTED_OPERATORS + "）");
            }
            for (String operator : operators) {
                if (!SUPPORTED_OPERATORS.contains(operator)) {
                    throw new IllegalStateException("yomi.captcha.arithmetic.operators 含不支持的运算符 [" + operator
                            + "]，取值 " + SUPPORTED_OPERATORS);
                }
            }
            requireRange(operandMin, 1, 99, "yomi.captcha.arithmetic.operand-min");
            requireRange(operandMax, operandMin, 99, "yomi.captcha.arithmetic.operand-max");
            requireRange(maxResult, 4, 9999, "yomi.captcha.arithmetic.max-result");
        }
    }

    /** 干扰滤镜参数 */
    @Data
    public static class Filter {

        /** 干扰线 */
        private Line line = new Line();

        /** 噪点 */
        private Noise noise = new Noise();

        /** 波纹 */
        private Wave wave = new Wave();

        void validate(Image image) {
            line.validate();
            noise.validate();
            wave.validate(image);
        }

        /** 干扰线（字符下层） */
        @Data
        public static class Line {

            /** 是否启用 */
            private boolean enabled = true;

            /** 条数 */
            private int count = 2;

            void validate() {
                requireRange(count, 0, 8, "yomi.captcha.filter.line.count");
            }
        }

        /** 噪点（字符上层） */
        @Data
        public static class Noise {

            /** 是否启用 */
            private boolean enabled = true;

            /** 噪点数（1 像素点） */
            private int dots = 45;

            void validate() {
                requireRange(dots, 0, 500, "yomi.captcha.filter.noise.dots");
            }
        }

        /** 波纹（字符上层，最后施加） */
        @Data
        public static class Wave {

            /** 是否启用 */
            private boolean enabled = true;

            /** 幅度（像素）：150×50 画布、28px 字号下超过 2 会破字，默认 1.5 */
            private double amplitude = 1.5;

            /** 波长（像素） */
            private double wavelength = 26;

            void validate(Image image) {
                if (amplitude < 0 || amplitude > image.getHeight() / 10.0) {
                    throw new IllegalStateException("yomi.captcha.filter.wave.amplitude 取值 0 ~ 图片高度的 1/10（当前 "
                            + amplitude + "，图片高度 " + image.getHeight() + "）；推荐不超过图片高度的 1/20");
                }
                if (wavelength <= 0 || wavelength > image.getHeight() * 4.0) {
                    throw new IllegalStateException("yomi.captcha.filter.wave.wavelength 取值 0 ~ 图片高度的 4 倍");
                }
            }
        }
    }

    /** 本地存储参数 */
    @Data
    public static class Local {

        /** 条目上限：超出按 Caffeine 淘汰策略回收，内存占用有上界 */
        private int maxEntries = 10000;

        void validate() {
            requireRange(maxEntries, 100, 1_000_000, "yomi.captcha.local.max-entries");
        }
    }

    /** 整数范围校验 */
    private static void requireRange(int value, int min, int max, String key) {
        if (value < min || value > max) {
            throw new IllegalStateException(key + " 取值 " + min + " ~ " + max + "（当前 " + value + "）");
        }
    }

    /** Duration 校验（须大于 0） */
    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 必须大于 0");
        }
    }

}
