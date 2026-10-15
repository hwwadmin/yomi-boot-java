package com.yomi.boot.java.component.redis.ops.sorted;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.base.utils.Lists;
import com.yomi.boot.java.component.redis.RedisTtl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.connection.SetCondition;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * redis字符型数据类型操作支持
 *
 * @author zwei
 */
public class StringRedisSupport {

    /** 字符串 KV 操作模板 */
    private final StringRedisTemplate redisTemplate;

    public StringRedisSupport(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 将 Redis 值转为字符串 */
    private String toStr(Object value) {
        String json;
        if (value instanceof String) {
            json = (String) value;
        } else {
            json = JsonUtils.toJson(value);
        }
        return json;
    }

    /** SET 字符串值 */
    public void set(final String key, final Object value) {
        redisTemplate.opsForValue().set(key, toStr(value));
    }

    /** SET 并设置过期时间（秒） */
    public void set(final String key, final Object value, final long timeout) {
        redisTemplate.opsForValue().set(key, toStr(value),
                Duration.of(RedisTtl.requirePositive(timeout, "set"), ChronoUnit.SECONDS));
    }

    /** SET 并设置过期时间 */
    public void set(final String key, final Object value, final long timeout, TimeUnit timeUtil) {
        redisTemplate.opsForValue().set(key, toStr(value),
                Duration.of(RedisTtl.requirePositive(timeout, "set"), timeUtil.toChronoUnit()));
    }

    /**
     * 仅在键不存在时写入值，并设置过期时间
     *
     * @param key Redis 键
     * @param value 写入值
     * @param timeout 过期时长，必须为正（0/负数会写成永久键）
     * @param timeUnit 时间单位
     * @return true-写入成功，false-键已存在或写入失败
     * @author zwei
     */
    public boolean setIfAbsent(final String key, final Object value, final long timeout, final TimeUnit timeUnit) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, toStr(value),
                Duration.of(RedisTtl.requirePositive(timeout, "setIfAbsent"), timeUnit.toChronoUnit()));
        return Objects.nonNull(result) && result;
    }

    /**
     * 仅在键已存在时原子更新值，并保留原有过期时间
     *
     * @param key Redis 键
     * @param value 新值
     * @return true-更新成功，false-键不存在或更新失败
     * @author zwei
     */
    public boolean setKeepTimeoutIfPresent(final String key, final Object value) {
        Boolean result = redisTemplate.execute((RedisCallback<Boolean>) connection -> connection.stringCommands().set(
                redisTemplate.getStringSerializer().serialize(key),
                redisTemplate.getStringSerializer().serialize(toStr(value)),
                SetCondition.ifPresent(),
                Expiration.keepTtl()
        ));
        return Objects.nonNull(result) && result;
    }

    /** GET 字符串值 */
    public String get(final String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 原子获取并删除键（使用 Redis GETDEL 命令）
     *
     * @author zwei
     */
    public String getAndDelete(final String key) {
        return redisTemplate.opsForValue().getAndDelete(key);
    }

    /** GET 字符串值 */
    public <T> T getAndDelete(final String key, Class<T> clazz) {
        String result = getAndDelete(key);
        return JsonUtils.parse(result, clazz);
    }

    /**
     * 取回值并把键的过期时间重置为 timeout
     *
     * @param timeout 过期时长，必须为正（0/负数会抹掉原过期时间或删键）；只读不续期请用 {@link #get(String)}
     */
    public String getAndExpire(final String key, final long timeout, final TimeUnit timeUnit) {
        return redisTemplate.opsForValue().getAndExpire(key,
                Duration.of(RedisTtl.requirePositive(timeout, "getAndExpire"), timeUnit.toChronoUnit()));
    }

    /** 取回值并按类型转换，同时把过期时间重置为 timeout（timeout 必须为正；只读不续期请用 {@link #get(String, Type)}） */
    public <T> T getAndExpire(final String key, Type type, final long timeout, final TimeUnit timeUnit) {
        String result = getAndExpire(key, timeout, timeUnit);
        return JsonUtils.parse(result, type);
    }

    /** GET 字符串值 */
    public <T> T get(final String key, Type type) {
        String o = get(key);
        return JsonUtils.parse(o, type);
    }

    /** GET 字符串值 */
    public <T> T get(final String key, Class<T> type) {
        String o = get(key);
        return JsonUtils.parse(o, type);
    }

    /** GET 字符串值 */
    public String getString(final String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /** GET 并解析为 Integer */
    public Integer getInteger(final String key) {
        String s = redisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(s)) return null;
        return Integer.valueOf(s);
    }

    /** GET 并解析为 Long */
    public Long getLong(final String key) {
        String s = redisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(s)) return null;
        return Long.valueOf(s);
    }

    /** MGET 批量取值 */
    public List<String> multiGet(Collection<String> keys) {
        List<String> ret = redisTemplate.opsForValue().multiGet(keys);
        return CollectionUtils.isNotEmpty(ret) ? ret : Lists.of();
    }

    /** MGET 批量取值并反序列化 */
    public <T> List<T> multiGet(Collection<String> keys, Type type) {
        List<String> value = multiGet(keys);
        //noinspection unchecked
        return value.stream().map(t -> (T) JsonUtils.parse(t, type)).collect(Collectors.toList());
    }

    /** MGET 批量取值并反序列化 */
    public <T> List<T> multiGet(Collection<String> keys, Class<T> type) {
        List<String> value = multiGet(keys);
        return value.stream().map(t -> (T) JsonUtils.parse(t, type)).collect(Collectors.toList());
    }

}
