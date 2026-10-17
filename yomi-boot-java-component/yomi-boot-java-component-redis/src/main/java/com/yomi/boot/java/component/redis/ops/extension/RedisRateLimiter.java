package com.yomi.boot.java.component.redis.ops.extension;

import com.yomi.boot.java.base.exception.UtilsException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * redis 限流器，通过令牌桶算法实现。
 * <p>
 * Redis 键前缀由构造参数 {@code keyPrefix} 指定（建议在应用配置层拼接应用名、模块名等），
 * 本类按限流 id 追加 hash tag 及 tokens / timestamp 后缀；id 含 "}" 会破坏
 * Redis Cluster hash tag 的槽位语义，构造 id 时需避开该字符
 * <p>
 * 语义说明：{@link #isAllowed} 在 Redis 调用异常时视为已限流（保护后端）；
 * {@link #acquire} 的等待会阻塞当前线程直至获得令牌或超时，Redis 异常直接抛出，与限流拒绝分开
 *
 * @author zwei
 */
@Slf4j
public class RedisRateLimiter {

    /** Redis 键片段：TOKENS_SUFFIX */
    private static final String TOKENS_SUFFIX = ":tokens";
    /** Redis 键片段：TIMESTAMP_SUFFIX */
    private static final String TIMESTAMP_SUFFIX = ":timestamp";

    /** 限流 key 长度上限，防止超大 id 撑大键与脚本参数 */
    private static final int MAX_KEY_LENGTH = 128;

    /**
     * 令牌桶限流 Lua 脚本（KEYS: tokens, timestamp；ARGV: rate, capacity, requested）
     */
    private static final String RATE_LIMITER_LUA = """
            --入参部分

            --限流的标识
            local tokens_key = KEYS[1]
            --令牌桶刷新的时间戳
            local timestamp_key = KEYS[2]
            --redis.log(redis.LOG_WARNING, "tokens_key " .. tokens_key)

            --令牌生产的速率
            local rate = tonumber(ARGV[1])
            --令牌桶的容量
            local capacity = tonumber(ARGV[2])
            --当前时间戳
            local now = redis.call('TIME')[1]
            --当前请求的令牌数
            local requested = tonumber(ARGV[3])
            --key 的失效时间（秒），由 Java 侧按两倍填满时间计算并保证不小于 1 秒
            local ttl = tonumber(ARGV[4])

            --redis.log(redis.LOG_WARNING, "rate " .. ARGV[1])
            --redis.log(redis.LOG_WARNING, "capacity " .. ARGV[2])
            --redis.log(redis.LOG_WARNING, "requested " .. ARGV[3])
            --redis.log(redis.LOG_WARNING, "ttl " .. ARGV[4])

            --当前桶中剩余令牌数。如果key不存在，初始化桶的容量数，即默认为满
            local last_tokens = tonumber(redis.call("get", tokens_key))
            if last_tokens == nil then
                last_tokens = capacity
            end
            --redis.log(redis.LOG_WARNING, "last_tokens " .. last_tokens)

            --上次刷新时间。如果key不存在，初始化为0
            local last_refreshed = tonumber(redis.call("get", timestamp_key))
            if last_refreshed == nil then
                last_refreshed = 0
            end
            --redis.log(redis.LOG_WARNING, "last_refreshed " .. last_refreshed)

            --时间差内能够恢复多少令牌
            --令牌恢复的速度是传入的rate值，所以在上次请求与本次请求之间的时间差(delta)内，就会产生delta*rate个令牌
            local delta = math.max(0, now - last_refreshed)
            local filled_tokens = math.min(capacity, last_tokens + (delta * rate))
            --申请的令牌足够
            local allowed = filled_tokens >= requested
            local new_tokens = filled_tokens

            --申请结果标识，0表示失败，1表示成功
            local allowed_num = 0
            --如果申请令牌通过，重新计算剩余令牌数。
            if allowed then
                new_tokens = filled_tokens - requested
                allowed_num = 1
            end

            --redis.log(redis.LOG_WARNING, "delta " .. delta)
            --redis.log(redis.LOG_WARNING, "filled_tokens " .. filled_tokens)
            --redis.log(redis.LOG_WARNING, "allowed_num " .. allowed_num)
            --redis.log(redis.LOG_WARNING, "new_tokens " .. new_tokens)

            --将本次申请后剩余令牌数，和key更新时间，写回redis，并设置过期时间。
            --写回不可加条件：令牌桶状态一旦不落库，下次请求按满桶处理，限流器即退化为完全放行
            redis.call("setex", tokens_key, ttl, new_tokens)
            --将key更新时间，写回redis，并设置过期时间
            redis.call("setex", timestamp_key, ttl, now)

            --返回令牌申请结果，和剩余令牌数
            return { allowed_num, new_tokens }
            """;

    /** 令牌桶 Redis 键前缀 */
    private final String keyPrefix;
    /** 限流脚本用的字符串 Redis */
    private final StringRedisTemplate stringRedisTemplate;

    /** 令牌桶 Lua 脚本 */
    private final RedisScript<List<Long>> script;

    public RedisRateLimiter(String keyPrefix, StringRedisTemplate stringRedisTemplate) {
        if (StringUtils.isBlank(keyPrefix)) {
            throw new IllegalArgumentException("RedisRateLimiter keyPrefix must not be blank");
        }
        if (stringRedisTemplate == null) {
            throw new IllegalArgumentException("RedisRateLimiter stringRedisTemplate must not be null");
        }
        this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
        this.stringRedisTemplate = stringRedisTemplate;
        DefaultRedisScript<List<Long>> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(RATE_LIMITER_LUA);
        //noinspection unchecked
        redisScript.setResultType((Class<List<Long>>) (Class<?>) List.class);
        this.script = redisScript;
    }

    /** 构造令牌桶 Redis 键列表 */
    private List<String> getKeys(String id) {
        // use `{}` around keys to use Redis Key hash tags (Redis Cluster)
        String prefix = this.keyPrefix + "{" + id + "}";
        String tokenKey = prefix + TOKENS_SUFFIX;
        String timestampKey = prefix + TIMESTAMP_SUFFIX;
        return Arrays.asList(tokenKey, timestampKey);
    }

    /**
     * 是否允许本次请求
     * Redis 调用异常时返回 false（视为已限流，保护后端）
     *
     * @param key             限流的 key，不能为空
     * @param replenishRate   令牌补充频率，每秒多少个
     * @param burstCapacity   桶容量
     * @param requestedTokens 请求令牌数量
     * @return true 未被限流，false 已限流
     */
    public boolean isAllowed(String key, int replenishRate, int burstCapacity, int requestedTokens) {
        validateKey(key);
        validateRateLimitParams(replenishRate, burstCapacity, requestedTokens);
        try {
            return tryAcquire(key, replenishRate, burstCapacity, requestedTokens);
        } catch (Exception e) {
            log.warn("redis限流器调用异常，视为已限流", e);
            return false;
        }
    }

    /**
     * 执行限流脚本，Redis 异常原样抛出
     */
    private boolean tryAcquire(String key, int replenishRate, int burstCapacity, int requestedTokens) {
        List<String> keys = getKeys(key);
        String[] scriptArgs = new String[]{
                replenishRate + "",
                burstCapacity + "",
                requestedTokens + "",
                ttlSeconds(replenishRate, burstCapacity) + ""
        };
        List<Long> list = this.stringRedisTemplate.execute(this.script, keys, (Object[]) scriptArgs);
        return list != null && !list.isEmpty() && list.getFirst() == 1L;
    }

    /**
     * 令牌桶状态 key 的失效时间（秒）
     * <p>
     * 取两倍填满桶所需时间，控制 key 数量；下界为 1 秒。
     * 该下界是必需的：{@code replenishRate > 2 * burstCapacity} 时两倍填满时间不足 1 秒，
     * TTL 至少为 1 秒，保证 Redis 中的桶状态能正确写回。
     */
    static int ttlSeconds(int replenishRate, int burstCapacity) {
        long ttl = (long) Math.floor(2.0 * burstCapacity / replenishRate);
        return (int) Math.max(1L, ttl);
    }

    /**
     * 等待获取令牌后执行
     * 等待期间会阻塞当前线程；Redis 调用异常时立即抛出，不与限流拒绝混淆
     *
     * @param key             限流的 key
     * @param replenishRate   令牌补充频率，每秒多少个
     * @param burstCapacity   桶容量
     * @param requestedTokens 请求令牌数量
     * @param ttl             等待时长（毫秒）
     * @param supplier        获取令牌后执行
     * @throws UtilsException 限流等待超时、等待被中断或 Redis 调用异常
     */
    public <T> T acquire(String key, int replenishRate, int burstCapacity, int requestedTokens, int ttl,
                         Supplier<T> supplier) {
        validateKey(key);
        if (supplier == null) {
            throw UtilsException.exception("redis rate limiter supplier must not be null");
        }
        validateRateLimitParams(replenishRate, burstCapacity, requestedTokens);
        if (ttl < 0) {
            throw UtilsException.exception("redis rate limiter wait ttl must not be negative");
        }
        long start = System.currentTimeMillis();
        long sleep = Math.max(100L, 1000L / replenishRate);
        while (true) {
            boolean allowed;
            try {
                allowed = tryAcquire(key, replenishRate, burstCapacity, requestedTokens);
            } catch (Exception e) {
                throw UtilsException.exception(e, "redis限流器调用异常");
            }
            if (allowed) {
                return supplier.get();
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed + sleep > ttl) {
                throw UtilsException.exception("已被限流，请等待");
            }
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw UtilsException.exception("限流等待被中断");
            }
        }
    }

    /** 校验限流键非空 */
    private static void validateKey(String key) {
        if (StringUtils.isBlank(key)) {
            throw UtilsException.exception("redis rate limiter key must not blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw UtilsException.exception("redis rate limiter key too long, length: {}", key.length());
        }
    }

    /** 校验令牌桶参数合法 */
    private static void validateRateLimitParams(int replenishRate, int burstCapacity, int requestedTokens) {
        if (replenishRate < 1) {
            throw new IllegalArgumentException("replenishRate must be positive");
        }
        if (burstCapacity < 1) {
            throw new IllegalArgumentException("burstCapacity must be positive");
        }
        if (requestedTokens < 1) {
            throw new IllegalArgumentException("requestedTokens must be positive");
        }
        if (requestedTokens > burstCapacity) {
            throw new IllegalArgumentException("requestedTokens must not exceed burstCapacity");
        }
    }

}
