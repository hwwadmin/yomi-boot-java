package com.yomi.boot.java.core.utils;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.MessageSource;

import java.util.Locale;

/**
 * 国际化消息工具类
 *
 * @author zwei
 */
public class I18nUtils {

    private I18nUtils() {

    }

    /**
     * 根据消息键和参数获取消息
     *
     * @param locale 语言
     * @param code   消息键，且不存在的对应code时即默认为返回的字符串
     * @return 获取国际化翻译值
     */
    public static String message(Locale locale, String code) {
        return message(locale, code, code, null);
    }

    /**
     * 根据消息键和参数获取消息
     *
     * @param locale 语言
     * @param code   消息键，且不存在的对应code时即默认为返回的字符串
     * @param args   参数
     * @return 获取国际化翻译值
     */
    public static String message(Locale locale, String code, Object[] args) {
        return message(locale, code, code, args);
    }

    /**
     * 根据消息键和参数获取消息
     *
     * @param locale         语言
     * @param code           消息键
     * @param defaultMessage 默认字符串
     * @param args           参数
     * @return 获取国际化翻译值
     */
    public static String message(Locale locale, String code, String defaultMessage, Object[] args) {
        if (StringUtils.isBlank(code)) return defaultMessage;
        MessageSource messageSource = SpringUtils.getBean(MessageSource.class);
        return messageSource.getMessage(code, args, defaultMessage, locale);
    }

}
