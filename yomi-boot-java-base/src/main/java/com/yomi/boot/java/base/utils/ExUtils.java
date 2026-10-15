package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.AbstractRuntimeException;
import org.apache.commons.lang3.StringUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 异常处理工具类
 *
 * @author zwei
 */
public class ExUtils {

    /** 无法提取消息时的默认文案 */
    private static final String DEFAULT_EXCEPTION_MSG = "Operation failed";

    /**
     * 堆栈字符串默认最大长度（与原 Hutool ExceptionUtil 一致）
     */
    private static final int DEFAULT_STACKTRACE_LIMIT = 3000;

    private ExUtils() {
    }

    /**
     * 获得异常消息
     */
    public static String getMessage(Throwable throwable) {
        if (Objects.isNull(throwable)) return null;
        return throwable.getMessage();
    }

    /**
     * 获取异常堆栈信息字符串
     */
    public static String stacktraceToString(Throwable throwable) {
        if (Objects.isNull(throwable)) return null;
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        String exceptionStr = sw.toString();
        if (DEFAULT_STACKTRACE_LIMIT > 0 && exceptionStr.length() > DEFAULT_STACKTRACE_LIMIT) {
            return exceptionStr.substring(0, DEFAULT_STACKTRACE_LIMIT);
        }
        return exceptionStr;
    }

    /**
     * 判断是否由指定异常类引起
     */
    public static boolean isCausedBy(Throwable throwable, Class<? extends Exception> causeClasses) {
        return getCausedBy(throwable, causeClasses) != null;
    }

    /**
     * 判断是否由指定异常类引起
     */
    public static boolean isCausedBy(Throwable throwable, Class<? extends Exception>[] causeClasses) {
        if (causeClasses == null || causeClasses.length == 0) {
            return false;
        }
        return getCausedBy(throwable, causeClasses) != null;
    }

    /**
     * 判断是否是由当前系统的异常
     * AbstractRuntimeException的子类异常就是当前系统异常
     */
    public static boolean isCausedBySystem(Throwable throwable) {
        Throwable rootCause = getRootCause(throwable);
        return rootCause != null && ReflectUtils.isSubClass(rootCause.getClass(), AbstractRuntimeException.class);
    }

    /**
     * 获取异常的核心提示信息
     */
    public static String getCoreExceptionMsg(Throwable throwable) {
        if (throwable == null) return DEFAULT_EXCEPTION_MSG;
        String exceptionMsg = throwable.getMessage();
        if (StringUtils.isBlank(exceptionMsg) && throwable.getCause() != null) {
            exceptionMsg = throwable.getCause().getMessage();
        }
        if (StringUtils.isBlank(exceptionMsg)) return DEFAULT_EXCEPTION_MSG;
        String[] msgLines = exceptionMsg.split("\\r?\\n", 2);
        String firstLine = msgLines.length > 0 ? msgLines[0].trim() : DEFAULT_EXCEPTION_MSG;
        int colonSeparatorIndex = firstLine.indexOf(": ");
        return colonSeparatorIndex > -1 && colonSeparatorIndex < firstLine.length() - 2 ? firstLine.substring(colonSeparatorIndex + 2).trim() : firstLine;
    }

    /**
     * 沿 cause 链查找首个匹配指定类型的异常（复用 {@link #getThrowableList(Throwable)} 的循环 cause 保护）
     */
    @SafeVarargs
    private static Throwable getCausedBy(Throwable throwable, Class<? extends Exception>... causeClasses) {
        if (causeClasses == null || causeClasses.length == 0) {
            return null;
        }
        for (Throwable current : getThrowableList(throwable)) {
            for (Class<? extends Exception> causeClass : causeClasses) {
                if (causeClass != null && causeClass.isInstance(current)) {
                    return current;
                }
            }
        }
        return null;
    }

    /**
     * 获取异常链上所有异常（含循环 cause 保护）
     */
    private static List<Throwable> getThrowableList(Throwable throwable) {
        List<Throwable> list = new ArrayList<>();
        while (throwable != null && !list.contains(throwable)) {
            list.add(throwable);
            throwable = throwable.getCause();
        }
        return list;
    }

    /**
     * 获取异常链最尾端（最早发生）的异常
     */
    private static Throwable getRootCause(Throwable throwable) {
        List<Throwable> list = getThrowableList(throwable);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

}
