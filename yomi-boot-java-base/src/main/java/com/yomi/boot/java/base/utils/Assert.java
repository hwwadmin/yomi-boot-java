package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.AssertException;

import java.util.Objects;

/**
 * 断言工具类
 *
 * @author zwei
 */
public abstract class Assert {

    private Assert() {
    }

    /** 无自定义消息时的默认断言失败文案 */
    private static final String DEFAULT_MESSAGE = "[Assertion failed]";

    /** 抛出默认断言异常 */
    private static void ex() {
        throw AssertException.exception(DEFAULT_MESSAGE);
    }

    /** 按模板格式化后抛出断言异常 */
    private static void ex(String errorMsgTemplate, Object... params) {
        throw AssertException.exception(StrUtils.format(errorMsgTemplate, params));
    }

    /**
     * 断言为真
     */
    public static void isTrue(boolean expression) {
        if (!expression) ex();
    }

    /**
     * 断言为真
     */
    public static void isTrue(boolean expression, String errorMsgTemplate, Object... params) {
        if (!expression) ex(errorMsgTemplate, params);
    }

    /**
     * 断言为假
     */
    public static void isFalse(boolean expression) {
        if (expression) ex();
    }

    /**
     * 断言为假
     */
    public static void isFalse(boolean expression, String errorMsgTemplate, Object... params) {
        if (expression) ex(errorMsgTemplate, params);
    }

    /**
     * 断言对象为空
     */
    public static void isNull(Object object) {
        if (Objects.nonNull(object)) ex();
    }

    /**
     * 断言对象为空
     */
    public static void isNull(Object object, String errorMsgTemplate, Object... params) {
        if (Objects.nonNull(object)) ex(errorMsgTemplate, params);
    }

    /**
     * 断言对象不为空
     */
    public static void nonNull(Object object) {
        if (Objects.isNull(object)) ex();
    }

    /**
     * 断言对象不为空
     */
    public static void nonNull(Object object, String errorMsgTemplate, Object... params) {
        if (Objects.isNull(object)) ex(errorMsgTemplate, params);
    }

}
