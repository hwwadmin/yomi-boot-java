package com.yomi.boot.java.base.utils;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.helpers.MessageFormatter;

import java.util.Collection;
import java.util.Locale;

/**
 * 字符串工具类
 *
 * @author zwei
 */
public abstract class StrUtils {

    private StrUtils() {
    }

    /**
     * 使用 {@code ,} 连接字符串
     */
    public static String join(Collection<String> collection) {
        return join(collection, ",");
    }

    /**
     * 使用指定的分隔符连接字符串
     */
    public static String join(Collection<String> collection, String delimiter) {
        Assert.nonNull(delimiter, "delimiter must not be null");
        if (CollectionUtils.isEmpty(collection)) return "";
        return String.join(delimiter, collection);
    }

    /**
     * 使用 {@code {}} 按顺序替换参数（与 SLF4J 占位规则一致）。
     */
    public static String format(CharSequence template, Object... params) {
        if (template == null) {
            return "null";
        }
        if (ArrayUtils.isEmpty(params) || StringUtils.isBlank(template)) {
            return template.toString();
        }
        return MessageFormatter.arrayFormat(template.toString(), params).getMessage();
    }

    /**
     * 将下划线方式命名的字符串转换为驼峰式
     */
    public static String toCamelCase(CharSequence str) {
        if (str == null) {
            return null;
        }
        String s = str.toString();
        if (StringUtils.indexOf(s, '_') == StringUtils.INDEX_NOT_FOUND) {
            return s;
        }
        String[] parts = StringUtils.splitPreserveAllTokens(s, "_");
        StringBuilder sb = new StringBuilder(s.length());
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            String lower = StringUtils.lowerCase(part, Locale.ROOT);
            if (sb.isEmpty()) {
                sb.append(lower);
            } else {
                sb.append(StringUtils.capitalize(lower));
            }
        }
        return sb.toString();
    }

    /**
     * 将驼峰式命名的字符串转换为下划线方式
     */
    public static String toUnderlineCase(CharSequence str) {
        if (str == null) {
            return null;
        }
        return StringUtils.join(StringUtils.splitByCharacterTypeCamelCase(str.toString()), '_')
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 将字符串首字母转为大写，其余字符保持不变
     */
    public static String upperFirst(String str) {
        if (StringUtils.isBlank(str)) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

}
