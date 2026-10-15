package com.yomi.boot.java.base.utils;

import org.apache.commons.collections4.CollectionUtils;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * List工具类
 *
 * @author zwei
 */
public abstract class Lists {

    private Lists() {
    }

    /**
     * 将元素转为可变列表
     */
    @SafeVarargs
    public static <T> List<T> of(final T... elements) {
        if (elements == null || elements.length == 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(elements));
    }

    /**
     * 将数组转为可变列表
     */
    public static <T> List<T> ofArray(T[] array) {
        if (array == null || array.length == 0) {
            return of();
        }
        return new ArrayList<>(Arrays.asList(array));
    }

    /**
     * 映射列表元素并过滤 {@code null} 元素及映射结果为 {@code null} 的项
     */
    public static <T, R> List<R> toList(final List<T> list, final Function<? super T, ? extends R> mapper) {
        Assert.nonNull(mapper);
        if (CollectionUtils.isEmpty(list)) {
            return of();
        }
        List<R> result = new ArrayList<>(list.size());
        for (T item : list) {
            if (item == null) {
                continue;
            }
            R mapped = mapper.apply(item);
            if (mapped != null) {
                result.add(mapped);
            }
        }
        return result;
    }

    /**
     * 将列表转为 Map，键由 {@code kMapper} 提取，值为元素本身
     */
    public static <T, K> Map<K, T> toMap(final List<T> list, final Function<T, K> kMapper) {
        return toMap(list, kMapper, Function.identity());
    }

    /**
     * 将列表转为 Map，跳过 {@code null} 元素；键冲突时后者覆盖前者
     */
    public static <T, K, V> Map<K, V> toMap(final List<T> list, final Function<T, K> kMapper, final Function<T, V> vMapper) {
        Assert.nonNull(kMapper);
        Assert.nonNull(vMapper);
        if (CollectionUtils.isEmpty(list)) {
            return new HashMap<>();
        }
        Map<K, V> map = new HashMap<>(list.size());
        for (T item : list) {
            if (item == null) {
                continue;
            }
            K key = kMapper.apply(item);
            if (key == null) {
                continue;
            }
            map.put(key, vMapper.apply(item));
        }
        return map;
    }

    /**
     * 按分组键聚合列表，跳过 {@code null} 元素
     */
    public static <K, T> Map<K, List<T>> toGroup(final List<T> list, final Function<T, K> mapper) {
        Assert.nonNull(mapper);
        if (CollectionUtils.isEmpty(list)) {
            return new HashMap<>();
        }
        Map<K, List<T>> groups = new HashMap<>();
        for (T item : list) {
            if (item == null) {
                continue;
            }
            K key = mapper.apply(item);
            if (key == null) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return groups;
    }

    /**
     * 将列表转为 Set，跳过 {@code null} 元素
     */
    public static <T> Set<T> toSet(final List<T> list) {
        if (CollectionUtils.isEmpty(list)) {
            return new HashSet<>();
        }
        Set<T> set = new HashSet<>();
        for (T item : list) {
            if (item != null) {
                set.add(item);
            }
        }
        return set;
    }

    /**
     * 映射列表元素并转为 Set，跳过 {@code null} 元素及映射结果为 {@code null} 的项
     */
    public static <T, V> Set<V> toSet(final List<T> list, final Function<T, V> mapper) {
        Assert.nonNull(mapper);
        if (CollectionUtils.isEmpty(list)) {
            return new HashSet<>();
        }
        Set<V> set = new HashSet<>();
        for (T item : list) {
            if (item == null) {
                continue;
            }
            V mapped = mapper.apply(item);
            if (mapped != null) {
                set.add(mapped);
            }
        }
        return set;
    }

    /**
     * 过滤列表中的 {@code null} 元素
     */
    public static <T> List<T> filterNull(final List<T> list) {
        if (list == null) {
            return of();
        }
        List<T> result = new ArrayList<>(list.size());
        for (T item : list) {
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 返回第一个非 {@code null} 元素；列表为 {@code null}、空或无非空元素时返回 {@code null}
     */
    public static <T> T getFirst(final List<T> list) {
        if (CollectionUtils.isEmpty(list)) {
            return null;
        }
        for (T item : list) {
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    /**
     * 随机返回列表中的一个元素（含 {@code null} 项）；列表为 {@code null} 或空时返回 {@code null}
     */
    public static <T> T random(final List<T> list) {
        if (CollectionUtils.isEmpty(list)) {
            return null;
        }
        int index = ThreadLocalRandom.current().nextInt(list.size());
        return list.get(index);
    }

}
