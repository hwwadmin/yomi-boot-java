package com.yomi.boot.java.component.idempotency.repository;

import com.yomi.boot.java.component.idempotency.entity.IdempotencyRecord;

import java.time.Instant;

/**
 * 幂等记录仓储（端口）：sys_idempotency_record 的持久化唯一出入口
 * <p>
 * 组件内其余代码（守卫/僵死清理）都经本接口访问，不直接调用 Mapper；
 * 默认实现 {@link IdempotencyRecordRepository}，宿主可自行实现本接口并注册 Bean 替换默认实现。
 *
 * @author zwei
 */
public interface IIdempotencyRecordRepository {

    /** 登记幂等记录（事务内先插占位） */
    void save(IdempotencyRecord row);

    /** 标记完成并写入结果缓存 */
    void markCompleted(Long id, String resultType, String resultPayload);

    /** 按 namespace + 幂等键查记录；不存在返回 null */
    IdempotencyRecord findByNamespaceAndKey(String namespace, String idempotencyKey);

    /** 表内最小的 {@code expire_time}（表空返回 null）：保留期清理的落后度量，单次取索引首项 */
    Instant findOldestExpireTime();

    /** 清理僵死的处理中记录（早于阈值），返回删除行数 */
    long deleteStaleProcessing(Instant before, int limit);

    /** 清理过期记录（早于阈值），返回删除行数 */
    long deleteExpired(Instant before, int limit);

}
