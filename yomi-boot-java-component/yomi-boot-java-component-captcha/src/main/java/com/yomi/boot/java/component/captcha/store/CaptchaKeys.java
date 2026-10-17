package com.yomi.boot.java.component.captcha.store;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 验证码相关存储键构造
 * <p>
 * 句柄为 128 位随机值、不可枚举，直接作为键后缀，答案按原文存储。
 * 前缀的合法性在这里校验：本类是唯一落键方，store=local 不构造本对象。
 *
 * @author zwei
 */
public class CaptchaKeys {

    /** 规范化后的存储前缀 */
    @Getter
    private final String prefix;

    public CaptchaKeys(String prefix) {
        if (StringUtils.isBlank(prefix)) {
            throw new IllegalStateException("yomi.captcha.store-prefix 不能为空");
        }
        if (!prefix.equals(prefix.trim())) {
            throw new IllegalStateException("yomi.captcha.store-prefix 不能含首尾空白（当前 [" + prefix + "]）");
        }
        if (prefix.indexOf('{') >= 0 || prefix.indexOf('}') >= 0) {
            // `${XXX}` 未解析时占位符会原样落到这里（Boot 绑定不强求解析），下面的报错单独点出这种情况
            boolean unresolvedPlaceholder = prefix.contains("${");
            throw new IllegalStateException("yomi.captcha.store-prefix 不能包含 Redis hash tag 字符 { 或 }（当前 [" + prefix + "]）"
                    + (unresolvedPlaceholder ? "：值形如 ${...} 说明占位符未解析，请检查对应配置项或环境变量是否存在" : ""));
        }
        String normalized = normalizePrefix(prefix);
        if (StringUtils.isBlank(normalized)) {
            throw new IllegalStateException("yomi.captcha.store-prefix 不能为空");
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
