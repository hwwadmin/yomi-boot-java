package com.yomi.boot.java.component.redis.ops.bloom;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Objects;

/**
 * 基于 Redis 字符串位图的布隆过滤器。
 * <p>
 * 使用 {@link org.springframework.data.redis.core.ValueOperations#setBit} / {@code getBit} 存储，
 * 与 {@link BitSetBloomFilter} 使用相同的哈希与下标映射规则。
 *
 * @author zwei
 */
public class BloomFilterRedisSupport {

    /** 默认哈希次数 */
    public static final int DEFAULT_HASH_FUNCTIONS = 8;
    /**
     * 默认位图长度（bit）
     * 该默认参数下约 10 万元素时误判率约 1%，插入量继续增长误判率将随之上升；
     * 大容量场景用 {@link BloomFilterSpec#of} 按期望插入量计算参数
     */
    public static final int DEFAULT_BIT_SIZE = 1_000_000;

    /** Redis 字符串操作 */
    private final RedisTemplate<String, Object> redisTemplate;

    public BloomFilterRedisSupport(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    }

    /**
     * 向布隆过滤器添加元素。
     */
    public void put(int hashFunctions, int bitSize, String key, String value) {
        validateParams(hashFunctions, bitSize, key, value);
        int[] hashes = BitSetBloomFilter.createHashes(value, hashFunctions);
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] rawKey = rawKey(key);
            for (int hash : hashes) {
                long offset = BitSetBloomFilter.bitIndex(hash, bitSize);
                connection.stringCommands().setBit(rawKey, offset, true);
            }
            return null;
        });
    }

    /** 布隆过滤器写入 */
    public void put(BloomFilterSpec spec, String key, String value) {
        put(spec.hashFunctions(), spec.bitSize(), key, value);
    }

    /** 布隆过滤器写入 */
    public void put(String key, String value) {
        put(DEFAULT_HASH_FUNCTIONS, DEFAULT_BIT_SIZE, key, value);
    }

    /**
     * 判断元素是否可能存在（存在误判：可能返回 true 但实际未插入）。
     */
    public boolean contain(int hashFunctions, int bitSize, String key, String value) {
        validateParams(hashFunctions, bitSize, key, value);
        int[] hashes = BitSetBloomFilter.createHashes(value, hashFunctions);
        List<Object> bits = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] rawKey = rawKey(key);
            for (int hash : hashes) {
                long offset = BitSetBloomFilter.bitIndex(hash, bitSize);
                connection.stringCommands().getBit(rawKey, offset);
            }
            return null;
        });
        if (bits == null || bits.size() != hashes.length) {
            return false;
        }
        for (Object bit : bits) {
            if (!Boolean.TRUE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    /** 单 key 布隆过滤器是否可能包含元素 */
    public boolean contain(BloomFilterSpec spec, String key, String value) {
        return contain(spec.hashFunctions(), spec.bitSize(), key, value);
    }

    /** 按 spec 解析 key 后判断是否可能存在 */
    public boolean contain(String key, String value) {
        return contain(DEFAULT_HASH_FUNCTIONS, DEFAULT_BIT_SIZE, key, value);
    }

    /** 拼接布隆过滤器 Redis 键 */
    private byte[] rawKey(String key) {
        return redisTemplate.getStringSerializer().serialize(key);
    }

    /** 校验布隆过滤器参数 */
    private static void validateParams(int hashFunctions, int bitSize, String key, String value) {
        if (hashFunctions < 1) {
            throw new IllegalArgumentException("hashFunctions must be positive");
        }
        if (bitSize < 1) {
            throw new IllegalArgumentException("bitSize must be positive");
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

}
