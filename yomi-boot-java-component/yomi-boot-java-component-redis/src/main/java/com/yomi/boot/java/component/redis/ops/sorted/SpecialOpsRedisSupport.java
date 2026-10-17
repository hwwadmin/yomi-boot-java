package com.yomi.boot.java.component.redis.ops.sorted;

import com.yomi.boot.java.component.redis.RedisTtl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * redis特殊操作支持
 *
 * @author zwei
 */
@SuppressWarnings("unused")
@Slf4j
public class SpecialOpsRedisSupport {

    /** SCAN 每次建议遍历的槽位数，平衡遍历轮次与单次返回量 */
    private static final long DEFAULT_SCAN_COUNT = 1000L;

    /** 按模式删除时单批 UNLINK 的 key 数量，控制单次内存占用 */
    private static final int DEFAULT_DELETE_BATCH_SIZE = 500;

    /** 自增并设置过期时间（原子执行），ARGV: 步长、过期毫秒数 */
    private static final RedisScript<Long> INCR_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            "local value = redis.call('INCRBY', KEYS[1], ARGV[1]) "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
                    + "return value", Long.class);

    /** 底层 Redis 操作 */
    private final RedisTemplate<String, Object> redisTemplate;

    public SpecialOpsRedisSupport(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 模糊匹配前缀删除
     */
    public void delByPre(String pre) {
        if (!StringUtils.hasText(pre)) return;
        deleteByPattern(pre + "*");
    }

    /**
     * 按模式分批 SCAN 并删除，匹配的 key 分批处理、不整体载入内存。
     *
     * @param pattern 键匹配模式
     * @return 实际删除的键数量
     */
    public long deleteByPattern(String pattern) {
        return deleteByPattern(pattern, DEFAULT_SCAN_COUNT, DEFAULT_DELETE_BATCH_SIZE);
    }

    /** 按模式 SCAN 分批 UNLINK 删除，可指定 scan count 与单批大小 */
    public long deleteByPattern(String pattern, long scanCount, int batchSize) {
        if (!StringUtils.hasText(pattern) || batchSize < 1) {
            return 0;
        }
        List<String> batch = new ArrayList<>(Math.min(batchSize, 1024));
        long[] deleted = {0};
        scan(pattern, scanCount, item -> {
            batch.add(new String(item, StandardCharsets.UTF_8));
            if (batch.size() >= batchSize) {
                deleted[0] += deleteBatch(batch);
                batch.clear();
            }
        });
        if (!batch.isEmpty()) {
            deleted[0] += deleteBatch(batch);
        }
        return deleted[0];
    }

    /** 分批删除 Redis 键 */
    private long deleteBatch(Collection<String> keys) {
        if (CollectionUtils.isEmpty(keys)) {
            return 0;
        }
        // UNLINK 异步释放内存，大批 key 删除不会阻塞 Redis 主线程
        Long ret = redisTemplate.unlink(keys);
        return ret != null ? ret : 0;
    }

    /**
     * 模糊匹配
     */
    public Map<String, Integer> findByPre(String prefix) {
        if (!StringUtils.hasText(prefix)) return null;
        Map<String, Integer> ret = new HashMap<>();
        scan(prefix + "*", item -> {
            String key = new String(item, StandardCharsets.UTF_8);
            Object v = redisTemplate.opsForValue().get(key);
            ret.put(key, v == null ? null : Integer.parseInt(String.valueOf(v)));
        });
        return ret;
    }

    /**
     * 获取符合条件的 key
     * 通过 SCAN 分批遍历，数据量大时
     * 不会阻塞 Redis 线程
     *
     * @param pattern 表达式
     */
    public List<String> keys(String pattern) {
        return keys(pattern, DEFAULT_SCAN_COUNT);
    }

    /** 按模式 SCAN 收集键名（数据量大时不阻塞 Redis） */
    public List<String> keys(String pattern, long count) {
        List<String> keys = new ArrayList<>();
        this.scan(pattern, count, item -> {
            //符合条件的key
            String key = new String(item, StandardCharsets.UTF_8);
            keys.add(key);
        });
        return keys;
    }

    /**
     * scan 实现（键以字符串消费）
     * 与字节型重载方法名区分，两个重载在泛型擦除后签名不同
     */
    public void scanKeys(String pattern, Consumer<String> consumer) {
        scan(pattern, item -> consumer.accept(new String(item, StandardCharsets.UTF_8)));
    }

    /**
     * scan 实现
     *
     * @param pattern  表达式
     * @param consumer 对迭代到的key进行操作
     */
    public void scan(String pattern, Consumer<byte[]> consumer) {
        scan(pattern, DEFAULT_SCAN_COUNT, consumer);
    }

    /** 按模式 SCAN 遍历键（字节形式回调） */
    public void scan(String pattern, long count, Consumer<byte[]> consumer) {
        this.redisTemplate.execute((RedisConnection connection) -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(
                    ScanOptions.scanOptions().count(count).match(pattern).build())) {
                // 异常（含 consumer 业务异常）原样抛出，保留原始类型
                cursor.forEachRemaining(consumer);
            }
            return null;
        });
    }

    /**
     * 初始化自增长值
     */
    public void setIncr(String key, Long value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 获取自增长值并将计数加一
     * 返回自增前的值，首次调用返回 0
     */
    public Long getIncr(String key) {
        Long value = redisTemplate.opsForValue().increment(key);
        return value == null ? null : value - 1;
    }

    /**
     * Redis原子自增操作, 更适合一次性使用的场景
     */
    public Long incr(String key) {
        return redisTemplate.opsForValue().increment(key);
    }

    /**
     * Redis原子自增操作, 更适合一次性使用的场景
     * 自增与过期时间在同一段 Lua 脚本中原子执行，每次调用重置过期时间（滑动窗口语义）
     *
     * @param timeout 过期时长，必须为正（脚本内 PEXPIRE 收到 0/负数会直接删掉刚自增的键）
     */
    public Long incr(String key, final long timeout, final TimeUnit unit) {
        return redisTemplate.execute(INCR_EXPIRE_SCRIPT, List.of(key), 1L,
                unit.toMillis(RedisTtl.requirePositive(timeout, "incr")));
    }

    /**
     * Redis原子自增操作, 更适合一次性使用的场景
     *
     * @param key   key
     * @param delta 步长
     * @return 自增后的值
     */
    public Long incr(String key, Long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * Redis原子自增操作, 更适合一次性使用的场景
     * 自增与过期时间在同一段 Lua 脚本中原子执行，每次调用重置过期时间（滑动窗口语义）
     *
     * @param key   key
     * @param delta 步长
     * @param timeout 过期时长，必须为正（脚本内 PEXPIRE 收到 0/负数会直接删掉刚自增的键）
     * @return 自增后的值
     */
    public Long incr(String key, Long delta, final long timeout, final TimeUnit unit) {
        return redisTemplate.execute(INCR_EXPIRE_SCRIPT, List.of(key), delta,
                unit.toMillis(RedisTtl.requirePositive(timeout, "incr")));
    }

}
