package com.yomi.boot.java.component.idempotency.store;

import com.yomi.boot.java.component.idempotency.config.IdempotencyProperties;
import com.yomi.boot.java.component.idempotency.core.IdempotencyKey;
import com.yomi.boot.java.component.idempotency.support.IdempotencyResultJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Type;
import java.time.Duration;

/**
 * 幂等成功结果的 Redis 缓存（第一层快读）
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyRedisStore {

    /** 业务结果为 null 时的缓存占位，与 JSON 对象 "{}" 区分 */
    private static final String CACHED_NULL_PAYLOAD = "__YOMI_IDEM_NULL__";

    /** 结果缓存读写 */
    private final StringRedisTemplate stringRedisTemplate;

    /** 缓存键前缀 */
    private final IdempotencyKeys keys;

    /** 缓存 TTL 等配置 */
    private final IdempotencyProperties properties;

    /**
     * 读缓存；Redis 异常时 fail-open 返回 miss，由锁 + DB 兜底
     *
     * @param type 完整泛型返回类型（{@code Result<OrderVO>} 而非裸 Result），重放不丢里层泛型
     */
    public IdempotencyCacheLoad load(IdempotencyKey key, Type type) {
        String redisKey = keys.resultKey(key);
        try {
            String payload = stringRedisTemplate.opsForValue().get(redisKey);
            if (payload == null) {
                return IdempotencyCacheLoad.miss();
            }
            if (CACHED_NULL_PAYLOAD.equals(payload)) {
                return IdempotencyCacheLoad.hit(null);
            }
            return IdempotencyCacheLoad.hit(IdempotencyResultJson.unmarshal(payload, type));
        } catch (Exception e) {
            log.warn("[幂等]结果缓存读取失败，将走 DB 路径 namespace={} key={} redisKey={}",
                    key.namespace(), key.idempotencyKey(), redisKey, e);
            return IdempotencyCacheLoad.miss();
        }
    }

    /** 写入结果并设置 TTL */
    public void save(IdempotencyKey key, Object value) {
        String redisKey = keys.resultKey(key);
        String payload;
        if (value == null) {
            payload = CACHED_NULL_PAYLOAD;
        } else {
            payload = IdempotencyResultJson.marshal(value);
            if (StringUtils.isBlank(payload)) {
                payload = "{}";
            }
        }
        Duration ttl = properties.getResultCacheTtl();
        stringRedisTemplate.opsForValue().set(redisKey, payload, ttl);
    }

    /** 写缓存失败不抛错，避免掩盖已成功的业务 */
    public void saveQuietly(IdempotencyKey key, Object value) {
        try {
            save(key, value);
        } catch (Exception e) {
            log.error("[幂等]结果缓存写入失败，业务已成功 namespace={} key={} redisKey={}",
                    key.namespace(), key.idempotencyKey(), keys.resultKey(key), e);
        }
    }

}
