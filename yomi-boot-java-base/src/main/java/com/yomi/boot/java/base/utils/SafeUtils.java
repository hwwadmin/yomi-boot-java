package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.function.Execute;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 安全操作工具类
 *
 * @author zwei
 */
@Slf4j
public abstract class SafeUtils {

    private SafeUtils() {
    }

    /**
     * 执行 supplier，异常时返回 {@code null}（不记日志）。
     */
    public static <T> T get(Supplier<T> supplier) {
        return get(supplier, null);
    }

    /**
     * 执行 supplier，异常时返回 defaultValue（不记日志）。
     */
    public static <T> T get(Supplier<T> supplier, T defaultValue) {
        if (Objects.isNull(supplier)) return defaultValue;
        try {
            return supplier.get();
        } catch (Exception e) {
            // 不打印错误日志
            return defaultValue;
        }
    }

    /**
     * 执行 supplier，异常时记 error 日志并返回 {@code null}。
     */
    public static <T> T getAndLogError(Supplier<T> supplier) {
        return getAndLogError(supplier, null);
    }

    /**
     * 执行 supplier，异常时记 error 日志并返回 defaultValue。
     */
    public static <T> T getAndLogError(Supplier<T> supplier, T defaultValue) {
        if (Objects.isNull(supplier)) return defaultValue;
        try {
            return supplier.get();
        } catch (Exception e) {
            log.error("SafeUtils#get", e);
            return defaultValue;
        }
    }

    /**
     * 执行无参逻辑；异常时记录 error 日志，不向调用方抛出。
     */
    public static void apply(Execute execute) {
        if (Objects.isNull(execute)) return;
        try {
            execute.apply();
        } catch (Exception e) {
            log.error("SafeUtils#apply", e);
        }
    }

    /**
     * 对非空 obj 执行 consumer；异常时记录 error 日志，不向调用方抛出。
     */
    public static <T> void consumer(T obj, Consumer<T> consumer) {
        if (Objects.isNull(obj)) return;
        if (Objects.isNull(consumer)) return;
        try {
            consumer.accept(obj);
        } catch (Exception e) {
            log.error("SafeUtils#consumer", e);
        }
    }

}
