package com.yomi.boot.java.component.captcha.store;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 验证码相关存储键构造
 * <p>
 * 句柄为 128 位随机值（不可枚举），因此直接作为键后缀，答案不做散列（答案空间小，散列无收益）。
 *
 * @author zwei
 */
public class CaptchaKeys {

    /** 规范化后的存储前缀 */
    @Getter
    private final String prefix;

    public CaptchaKeys(String prefix) {
        String normalized = normalizePrefix(prefix);
        if (StringUtils.isBlank(normalized)) {
            throw new IllegalStateException("验证码存储前缀不能为空");
        }
        if (normalized.indexOf('{') >= 0 || normalized.indexOf('}') >= 0) {
            throw new IllegalStateException("验证码存储前缀不能包含 Redis hash tag 字符 { 或 }");
        }
        this.prefix = normalized;
    }

    /**
     * 挑战答案键：{@code prefix:captchaId}
     */
    public String challengeKey(String captchaId) {
        if (StringUtils.isBlank(captchaId)) {
            throw new IllegalArgumentException("验证码句柄不能为空");
        }
        return prefix + ":" + captchaId;
    }

    /**
     * 集成测试 / 运维清理用的 KEYS 模式（Standalone；Cluster 慎用 KEYS）
     */
    public String cleanupKeyPattern() {
        return prefix + ":*";
    }

    /**
     * 规范化存储前缀：去首尾空白与 {@code :}
     */
    public static String normalizePrefix(String prefix) {
        return StringUtils.strip(StringUtils.trimToEmpty(prefix), ":");
    }

}
