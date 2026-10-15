package com.yomi.boot.java.component.redis.ops;

import com.yomi.boot.java.base.utils.Lists;
import com.yomi.boot.java.component.redis.RedisTtl;
import com.yomi.boot.java.component.redis.config.CommonRedisTemplate;
import com.yomi.boot.java.component.redis.config.RedisConfiguredCondition;
import com.yomi.boot.java.component.redis.ops.bloom.BloomFilterRedisSupport;
import com.yomi.boot.java.component.redis.ops.sorted.*;
import com.yomi.boot.java.component.redis.ops.stream.StreamRedisSupport;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * redis调用封装
 *
 * @author zwei
 */
@Slf4j
@Getter
@Component
@Conditional(RedisConfiguredCondition.class)
public class RedisSupport {

    /** 通用 Redis 模板（对象序列化） */
    private final CommonRedisTemplate redisTemplate;

    /** 字符串 Redis 模板 */
    private final StringRedisTemplate stringRedisTemplate;

    /** Stream 监听容器 */
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> redisStreamListenerContainer;

    /** 字符串类型操作 */
    private StringRedisSupport ops4str;

    /** Hash 类型操作 */
    private HashRedisSupport ops4hash;

    /** List 类型操作 */
    private ListRedisSupport ops4list;

    /** Set 类型操作 */
    private SetRedisSupport ops4set;

    /** 有序 Set 类型操作 */
    private ZSetRedisSupport ops4zset;

    /** 键扫描等扩展操作 */
    private SpecialOpsRedisSupport ops4special;

    /** 布隆过滤器 */
    private BloomFilterRedisSupport bloomFilter;

    /** Stream 发布与消费 */
    private StreamRedisSupport ops4stream;

    public RedisSupport(CommonRedisTemplate redisTemplate,
                        StringRedisTemplate stringRedisTemplate,
                        StreamMessageListenerContainer<String, MapRecord<String, String, String>> redisStreamListenerContainer) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisStreamListenerContainer = redisStreamListenerContainer;
    }

    @PostConstruct
    public void init() {
        this.ops4str = new StringRedisSupport(this.stringRedisTemplate);
        this.ops4hash = new HashRedisSupport(this.redisTemplate);
        this.ops4list = new ListRedisSupport(this.redisTemplate);
        this.ops4set = new SetRedisSupport(this.stringRedisTemplate);
        this.ops4zset = new ZSetRedisSupport(this.stringRedisTemplate);
        this.ops4special = new SpecialOpsRedisSupport(this.redisTemplate);
        this.bloomFilter = new BloomFilterRedisSupport(this.redisTemplate);
        this.ops4stream = new StreamRedisSupport(this.stringRedisTemplate, this.redisStreamListenerContainer);
    }

    /**
     * 按模式查询匹配的键集合
     * 注意：结果全量物化到内存，大键空间用 {@link #keys(String, Consumer)} 流式消费
     *
     * @param pattern 键匹配模式
     * @return 匹配到的键集合，无结果时返回空集合
     * @author zwei
     */
    public Set<String> keys(final String pattern) {
        List<String> keys = getOps4special().keys(pattern);
        if (CollectionUtils.isEmpty(keys)) return new HashSet<>();
        return Lists.toSet(keys);
    }

    /**
     * 按模式流式消费匹配的键
     * 结果逐条消费不整体驻留内存，适合大键空间
     *
     * @param pattern     键匹配模式
     * @param keyConsumer 对匹配到的键进行操作
     * @author zwei
     */
    public void keys(final String pattern, final Consumer<String> keyConsumer) {
        getOps4special().scanKeys(pattern, keyConsumer);
    }

    /**
     * 为指定键设置过期时间，默认单位为秒
     *
     * @param key Redis 键
     * @param timeout 过期时长
     * @return 是否设置成功
     * @author zwei
     */
    public boolean expire(final String key, final long timeout) {
        return expire(key, timeout, TimeUnit.SECONDS);
    }

    /**
     * 为指定键设置过期时间
     *
     * @param key Redis 键
     * @param timeout 过期时长，必须为正（0/负数会被 Redis 当作删除键）
     * @param unit 时间单位
     * @return 是否设置成功
     * @author zwei
     */
    public boolean expire(final String key, final long timeout, final TimeUnit unit) {
        Boolean ret = redisTemplate.expire(key, Duration.of(RedisTtl.requirePositive(timeout, "expire"), unit.toChronoUnit()));
        return Objects.nonNull(ret) && ret;
    }

    /**
     * 获取键的剩余过期秒数
     * 永久键返回 -1，键不存在返回 -2
     *
     * @param key Redis 键
     * @return 剩余过期秒数
     * @author zwei
     */
    public long getExpireSeconds(final String key) {
        Long expire = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (Objects.isNull(expire)) return -2L;
        return expire;
    }

    /**
     * 在保留原剩余过期时间的前提下更新字符串值
     *
     * @param key Redis 键
     * @param value 新值
     * @author zwei
     */
    public void updateKeepTimeout(final String key, final Object value) {
        getOps4str().setKeepTimeoutIfPresent(key, value);
    }

    /**
     * 删除单个键
     *
     * @param key Redis 键
     * @return 是否删除成功
     * @author zwei
     */
    public boolean del(final String key) {
        Boolean ret = redisTemplate.delete(key);
        return Objects.nonNull(ret) && ret;
    }

    /**
     * 批量删除多个键
     *
     * @param keys Redis 键集合
     * @return 实际删除的键数量
     * @author zwei
     */
    public long del(final Collection<String> keys) {
        Long ret = redisTemplate.delete(keys);
        return Objects.nonNull(ret) ? ret : 0;
    }

    /**
     * 删除所有匹配给定模式的键
     *
     * @param pattern 键匹配模式
     * @return 实际删除的键数量
     * @author zwei
     */
    public long delByPattern(String pattern) {
        return getOps4special().deleteByPattern(pattern);
    }

    /**
     * 判断指定键是否存在
     *
     * @param key Redis 键
     * @return true-存在，false-不存在
     * @author zwei
     */
    public boolean isExist(final String key) {
        Boolean ret = redisTemplate.hasKey(key);
        return Objects.nonNull(ret) && ret;
    }

}
