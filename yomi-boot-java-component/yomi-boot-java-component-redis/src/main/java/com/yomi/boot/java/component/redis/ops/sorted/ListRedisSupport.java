package com.yomi.boot.java.component.redis.ops.sorted;

import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collection;
import java.util.List;

/**
 * redis列表数据类型操作支持
 *
 * @author zwei
 */
public class ListRedisSupport {

    /** List 结构操作模板 */
    private final RedisTemplate<String, Object> redisTemplate;

    public ListRedisSupport(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 列表头/尾入队 */
    public long leftPush(final String key, final Object value) {
        Long count = redisTemplate.opsForList().leftPush(key, value);
        return count == null ? 0 : count;
    }

    /** 列表头/尾入队 */
    public long leftPushAll(final String key, final Collection<?> values) {
        Long count = redisTemplate.opsForList().leftPushAll(key, values);
        return count == null ? 0 : count;
    }

    /** 列表头/尾出队 */
    public Object leftPop(final String key) {
        return redisTemplate.opsForList().leftPop(key);
    }

    /** 列表头/尾入队 */
    public long rightPush(final String key, final Object value) {
        Long count = redisTemplate.opsForList().rightPush(key, value);
        return count == null ? 0 : count;
    }

    /** 列表头/尾入队 */
    public long rightPushAll(final String key, final Collection<?> values) {
        Long count = redisTemplate.opsForList().rightPushAll(key, values);
        return count == null ? 0 : count;
    }

    /** 列表头/尾出队 */
    public Object rightPop(final String key) {
        return redisTemplate.opsForList().rightPop(key);
    }

    /** 列表头/尾出队 */
    public List<Object> rightPop(final String key, final long count) {
        return redisTemplate.opsForList().rightPop(key, count);
    }

    /** LRANGE 区间元素 */
    public List<Object> range(final String key, final int start, final int end) {
        // start=0，end=-1表示获取全部元素
        return redisTemplate.opsForList().range(key, start, end);
    }

    /** List 结构读写 */
    public void del(final String key, final Object value) {
        redisTemplate.opsForList().remove(key, 1, value);
    }

    /** List 结构读写 */
    public void trim(final String key, int start, int end) {
        redisTemplate.opsForList().trim(key, start, end);
    }

    /** List 结构读写 */
    public Long size(final String key) {
        return redisTemplate.opsForList().size(key);
    }

}
