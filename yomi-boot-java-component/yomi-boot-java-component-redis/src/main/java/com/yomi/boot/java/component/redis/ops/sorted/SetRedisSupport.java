package com.yomi.boot.java.component.redis.ops.sorted;

import com.yomi.boot.java.base.utils.JsonUtils;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * redis集合数据类型操作支持
 *
 * @author zwei
 */
public class SetRedisSupport {

    /** Set 结构操作模板 */
    private final StringRedisTemplate redisTemplate;

    public SetRedisSupport(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Set 结构读写 */
    public long add(final String key, final String... values) {
        Long count = redisTemplate.opsForSet().add(key, values);
        return count == null ? 0 : count;
    }

    /** GET 字符串值 */
    public <T> Set<T> get(String key, Class<T> tClass) {
        Set<String> set = redisTemplate.opsForSet().members(key);
        if (set == null) return null;
        if (String.class.equals(tClass)) //noinspection unchecked
            return (Set<T>) set;
        return set.stream().map(item -> JsonUtils.parse(item, tClass)).collect(Collectors.toSet());
    }

    /**
     * 注意：集合成员量大时一次性加载全部数据，大集合用 {@link #scan} 分页
     */
    public Set<String> get(String key) {
        return redisTemplate.opsForSet().members(key);
    }

    /**
     * 分页扫描集合的全部成员，结果逐条消费不整体驻留内存
     */
    public void scan(final String key, final long count, final Consumer<String> consumer) {
        try (Cursor<String> cursor = redisTemplate.opsForSet().scan(
                key, ScanOptions.scanOptions().count(count).build())) {
            cursor.forEachRemaining(consumer);
        }
    }

    /** Set 结构读写 */
    public long del(final String key, final String... values) {
        Long count = redisTemplate.opsForSet().remove(key, (Object[]) values);
        return count == null ? 0 : count;
    }

    /** Set 结构读写 */
    public long size(final String key) {
        Long size = redisTemplate.opsForSet().size(key);
        return size == null ? 0L : size;
    }

}
