package com.yomi.boot.java.component.redis.ops.sorted;

import com.yomi.boot.java.base.utils.JsonUtils;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;
import java.util.function.Consumer;

/**
 * redis有序集合数据类型操作支持
 *
 * @author zwei
 */
public class ZSetRedisSupport {

    /** 有序集合操作模板 */
    private final StringRedisTemplate redisTemplate;

    public ZSetRedisSupport(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 格式化 ZSet 成员为字符串 */
    private String formatMember(final Object value) {
        return JsonUtils.toJson(value);
    }

    /** ZSet 结构读写 */
    public Boolean add(final String key, final Object value, Double score) {
        return redisTemplate.opsForZSet().add(key, formatMember(value), score);
    }

    /** 有序集合操作 */
    public Double score(final String key, final Object value) {
        return redisTemplate.opsForZSet().score(key, formatMember(value));
    }

    /**
     * 注意：有序集合成员量大时一次性加载全部数据，大集合用 {@link #scan} 分页
     */
    public Set<ZSetOperations.TypedTuple<String>> getAll(final String key) {
        return redisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY);
    }

    /**
     * 分页扫描有序集合的全部成员，结果逐条消费不整体驻留内存
     */
    public void scan(final String key, final long count,
                     final Consumer<ZSetOperations.TypedTuple<String>> consumer) {
        try (Cursor<ZSetOperations.TypedTuple<String>> cursor = redisTemplate.opsForZSet().scan(
                key, ScanOptions.scanOptions().count(count).build())) {
            cursor.forEachRemaining(consumer);
        }
    }

    /** 有序集合操作 */
    public Set<ZSetOperations.TypedTuple<String>> getAllByRange(final String key, final Integer start,
                                                                final Integer end) {
        return redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
    }

    /** ZSet 结构读写 */
    public void incr(final String key, final Object value) {
        redisTemplate.opsForZSet().incrementScore(key, formatMember(value), 1);
    }

}
