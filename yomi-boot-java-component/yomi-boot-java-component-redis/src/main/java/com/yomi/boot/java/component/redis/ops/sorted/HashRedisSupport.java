package com.yomi.boot.java.component.redis.ops.sorted;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.base.utils.Lists;
import com.yomi.boot.java.component.redis.RedisTtl;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * redis哈希数据类型操作支持
 *
 * @author zwei
 */
@SuppressWarnings("unused")
public class HashRedisSupport {

    /** Hash 结构操作模板 */
    private final RedisTemplate<String, Object> redisTemplate;

    public HashRedisSupport(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Hash 结构读写 */
    public Set<Object> keys(final String key) {
        return redisTemplate.opsForHash().keys(key);
    }

    /** Hash 结构读写 */
    public void put(final String key, final String hKey, final Object value) {
        redisTemplate.opsForHash().put(key, hKey, value);
    }

    /** 写入字段并把整个键的过期时间设为 timeout（timeout 必须为正；0/负数会删掉刚写入的键） */
    public void put(final String key, final String hKey, final Object value, long timeout, final TimeUnit timeUnit) {
        redisTemplate.opsForHash().put(key, hKey, value);
        redisTemplate.expire(key, Duration.of(RedisTtl.requirePositive(timeout, "put"), timeUnit.toChronoUnit()));
    }

    /** Hash 结构读写 */
    public void putAll(final String key, final Map<Object, Object> values) {
        redisTemplate.opsForHash().putAll(key, values);
    }

    /** 批量写入字段并把整个键的过期时间设为 timeout（timeout 必须为正；0/负数会删掉刚写入的键） */
    public void putAll(final String key, final Map<String, Object> values, final long timeout, final TimeUnit unit) {
        redisTemplate.opsForHash().putAll(key, values);
        redisTemplate.expire(key, Duration.of(RedisTtl.requirePositive(timeout, "putAll"), unit.toChronoUnit()));
    }

    /** GET 字符串值 */
    public Object get(final String key, final String hKey) {
        return redisTemplate.opsForHash().get(key, hKey);
    }

    /**
     * hash字段不存在时返回 null
     */
    public <T> T get(final String key, final String hKey, final Class<T> clazz) {
        Object value = get(key, hKey);
        if (Objects.isNull(value)) return null;
        return JsonUtils.parse(JsonUtils.toJson(value), clazz);
    }

    /**
     * hash字段不存在时返回空集合
     */
    public <T> List<T> getList(final String key, final String hKey, final Class<T> clazz) {
        // 提供 list 的反序列化，仅适用于 list 数据
        Object value = get(key, hKey);
        if (Objects.isNull(value)) return Lists.of();
        return JsonUtils.jsonStr2List(JsonUtils.toJson(value), clazz);
    }

    /**
     * 注意：hash 字段量大时一次性加载全部数据，大 hash 用 {@link #scan} 分页
     */
    public Map<Object, Object> getAll(final String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    /**
     * 分页扫描 hash 的全部字段，结果逐条消费不整体驻留内存
     */
    public void scan(final String key, final long count, final Consumer<Map.Entry<Object, Object>> consumer) {
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(
                key, ScanOptions.scanOptions().count(count).build())) {
            cursor.forEachRemaining(consumer);
        }
    }

    /** Hash 结构读写 */
    public Object del(final String key, Object... hashKeys) {
        return redisTemplate.opsForHash().delete(key, hashKeys);
    }

    /** MGET 批量取值 */
    public List<Object> multiGet(final String key, final Collection<String> hKeys) {
        return redisTemplate.opsForHash().multiGet(key, new ArrayList<>(hKeys));
    }

    /** MGET 批量取值并反序列化 */
    public <T> List<T> multiGet(final String key, final Collection<String> hKeys, final Class<T> clazz) {
        return JsonUtils.jsonStr2List(JsonUtils.toJson(multiGet(key, hKeys)), clazz);
    }

}
