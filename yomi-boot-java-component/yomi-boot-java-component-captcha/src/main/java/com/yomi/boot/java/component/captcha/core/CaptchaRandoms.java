package com.yomi.boot.java.component.captcha.core;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 验证码随机源
 * <p>
 * 统一使用 {@link SecureRandom}：挑战句柄与答案都必须不可预测。
 * 句柄为 128 位随机值；雪花 ID 时间有序可枚举，枚举句柄等于枚举答案。
 *
 * @author zwei
 */
public final class CaptchaRandoms {

    /** 安全随机数源 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private CaptchaRandoms() {
    }

    /**
     * 生成 128 位随机挑战句柄（URL 安全，22 字符）
     */
    public static String nextId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /**
     * 生成渲染随机种子（同一文本 + 同一种子输出同一张图，便于测试复现）
     */
    public static long nextSeed() {
        return RANDOM.nextLong();
    }

    /**
     * 生成 {@code [0, bound)} 区间的随机数
     */
    public static int nextInt(int bound) {
        return RANDOM.nextInt(bound);
    }

}
