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
 * <p>键按记录库分段（{@link IdempotencyKeys#forDatasource}）：多数据源下同名 namespace + 幂等键不能互相命中
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

    /** 缓存 TTL 等配置 */
    private final IdempotencyProperties properties;

    /**
     * 读缓存；Redis 异常时返回 miss（只记 WARN），由锁 + DB 路径继续
     *
     * @param dsKeys 本次记录库的键集合（含库段）
     * @param type   完整泛型返回类型（如 {@code Result<OrderVO>}），重放不丢里层泛型
     */
    public IdempotencyCacheLoad load(IdempotencyKeys dsKeys, IdempotencyKey key, Type type) {
        String redisKey = dsKeys.resultKey(key);
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
    public void save(IdempotencyKeys dsKeys, IdempotencyKey key, Object value) {
        String redisKey = dsKeys.resultKey(key);
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

    /** 写缓存失败只记日志不抛错，业务成功结果不受影响 */
    public void saveQuietly(IdempotencyKeys dsKeys, IdempotencyKey key, Object value) {
        try {
            save(dsKeys, key, value);
        } catch (Exception e) {
            log.error("[幂等]结果缓存写入失败，业务已成功 namespace={} key={} redisKey={}",
                    key.namespace(), key.idempotencyKey(), dsKeys.resultKey(key), e);
        }
    }

}
