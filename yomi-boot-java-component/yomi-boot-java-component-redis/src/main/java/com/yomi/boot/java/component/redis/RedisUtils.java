package com.yomi.boot.java.component.redis;

import com.yomi.boot.java.component.redis.ops.RedisSupport;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;

/**
 * redis工具类
 *
 * @author zwei
 */
public abstract class RedisUtils {

    /** 聚合各 Redis 结构操作的门面 */
    private static volatile RedisSupport redisSupport;

    /**
     * 获取redis操作封装对象
     */
    public static RedisSupport ops() {
        if (Objects.nonNull(redisSupport)) return redisSupport;
        synchronized (RedisUtils.class) {
            if (Objects.nonNull(redisSupport)) return redisSupport;
            redisSupport = SpringUtils.getBean(RedisSupport.class);
        }
        return redisSupport;
    }

    /** 暴露 Object RedisTemplate */
    public static RedisTemplate<String, Object> redisTemplate() {
        return ops().getRedisTemplate();
    }

    /** 暴露 String RedisTemplate */
    public static StringRedisTemplate stringRedisTemplate() {
        return ops().getStringRedisTemplate();
    }

    /**
     * 获取redisson客户端
     */
    public static RedissonClient redisson() {
        return SpringUtils.getBean(RedissonClient.class);
    }

}
