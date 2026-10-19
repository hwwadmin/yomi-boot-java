package com.yomi.boot.java.component.idempotency.store;

import com.yomi.boot.java.component.idempotency.core.IdempotencyKey;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 幂等相关 Redis Key
 * <p>多数据源下每个记录库一段前缀（{@link #forDatasource}）：同一个 namespace + 幂等键落在不同库是两件事，
 * 键里不带库名会让缓存互相命中（A 库的结果被当成 B 库的结果重放）
 *
 * @author zwei
 */
@Getter
public class IdempotencyKeys {

    /** 配置项 store-prefix 解析后的前缀 */
    private final String prefix;

    public IdempotencyKeys(String prefix) {
        this.prefix = prefix;
    }

    /** 某个记录库的键集合：注册名并入前缀 */
    public IdempotencyKeys forDatasource(String datasource) {
        return new IdempotencyKeys(prefix + ":" + datasource);
    }

    /**
     * 成功结果缓存键
     * <p>带请求指纹时把指纹摘要并入键：缓存是快路径、最先被读到，键里不带指纹时"同键不同体"
     * 在缓存 TTL 内会命中上次结果，绕过库里的指纹校验
     */
    public String resultKey(IdempotencyKey key) {
        String base = prefix + ":result:" + key.namespace() + ":" + key.idempotencyKey();
        return StringUtils.isBlank(key.requestHash())
                ? base
                : base + ":fp:" + key.requestHash();
    }

    /** 同键串行化用的分布式锁键（不含指纹：同键的并发请求必须串行到同一把锁上，再由库内指纹校验判冲突） */
    public String lockKey(IdempotencyKey key) {
        return prefix + ":lock:" + key.namespace() + ":" + key.idempotencyKey();
    }

}
