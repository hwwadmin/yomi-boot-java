package com.yomi.boot.java.component.oss.config;

import org.apache.commons.lang3.StringUtils;

/**
 * 对象存储实现标识，对应 yml {@code yomi.oss.provider}。
 *
 * @author zwei
 */
public enum OssProvider {

    S3,
    RUSTFS,
    ALIOSS;

    /** 从配置字符串解析，大小写不敏感 */
    public static OssProvider fromConfig(String raw) {
        String value = StringUtils.lowerCase(StringUtils.trimToEmpty(raw));
        return switch (value) {
            case "s3" -> S3;
            case "rustfs" -> RUSTFS;
            case "alioss" -> ALIOSS;
            case "" -> throw new IllegalStateException("yomi.oss.provider 不能为空");
            default -> throw new IllegalStateException("yomi.oss.provider 不支持: " + raw);
        };
    }

    /** 写回 yml 风格的 provider 值 */
    public String configValue() {
        return name().toLowerCase();
    }

}
