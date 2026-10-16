package com.yomi.boot.java.base.utils;

import lombok.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Map 工具类
 *
 * @author zwei
 */
public abstract class Maps {

    private Maps() {
    }

    /** 创建空的 {@link HashMap} */
    public static <K, V> Map<K, V> of() {
        return new HashMap<>();
    }

    /** 创建空的 {@link ConcurrentHashMap} */
    public static <K, V> Map<K, V> ofSafe() {
        return new ConcurrentHashMap<>();
    }

    /** map 为 null 或空时返回 true */
    public static <K, V> boolean isEmpty(Map<K, V> map) {
        if (Objects.isNull(map)) return true;
        return map.isEmpty();
    }

    /** map 非空时返回 true */
    public static <K, V> boolean isNotEmpty(Map<K, V> map) {
        return !isEmpty(map);
    }

    /** 映射值；入参 map 为 null 时返回 null */
    public static <K, V, T> Map<K, T> map(Map<K, V> map, @NonNull Function<V, T> valueMapper) {
        if (Objects.isNull(map)) return null;
        Map<K, T> newMap = of();
        map.forEach((k, v) -> newMap.put(k, valueMapper.apply(v)));
        return newMap;
    }

    /** 映射键与值；入参 map 为 null 时返回 null */
    public static <K, V, K2, V2> Map<K2, V2> map(Map<K, V> map, @NonNull Function<K, K2> keyMapper, @NonNull Function<V, V2> valueMapper) {
        if (Objects.isNull(map)) return null;
        Map<K2, V2> newMap = of();
        map.forEach((k, v) -> newMap.put(keyMapper.apply(k), valueMapper.apply(v)));
        return newMap;
    }

    /** 浅拷贝为新的 HashMap；source 为 null 时返回 null */
    public static <K, V> Map<K, V> clone(Map<K, V> source) {
        if (Objects.isNull(source)) return null;
        return new HashMap<>(source);
    }

    /** 按键过滤 */
    public static <K, V> Map<K, V> filterK(Map<K, V> map, Predicate<? super K> filter) {
        return filter(map, filter, null);
    }

    /** 按值过滤 */
    public static <K, V> Map<K, V> filterV(Map<K, V> map, Predicate<? super V> filter) {
        return filter(map, null, filter);
    }

    /** 按键、值条件过滤；入参 map 为 null 时返回 null，空 map 返回空结果 */
    public static <K, V> Map<K, V> filter(Map<K, V> map, Predicate<? super K> keyFilter, Predicate<? super V> valueFilter) {
        if (Objects.isNull(map)) return null;
        if (map.isEmpty()) return of();
        Map<K, V> newMap = of();
        map.forEach((k, v) -> {
            if (Objects.nonNull(keyFilter) && (!keyFilter.test(k))) return;
            if (Objects.nonNull(valueFilter) && (!valueFilter.test(v))) return;
            newMap.put(k, v);
        });
        return newMap;
    }

}
