package com.yomi.boot.java.component.idempotency.store;

/**
 * Redis 结果缓存读取结果：区分「未命中」与「命中（值可为 null）」
 *
 * @author zwei
 */
public record IdempotencyCacheLoad(boolean hit, Object value) {

    /** Redis 未命中缓存 */
    public static IdempotencyCacheLoad miss() {
        return new IdempotencyCacheLoad(false, null);
    }

    /** Redis 命中缓存（值可为 null） */
    public static IdempotencyCacheLoad hit(Object value) {
        return new IdempotencyCacheLoad(true, value);
    }

}
