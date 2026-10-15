package com.yomi.boot.java.component.idempotency.repository;

import com.yomi.boot.java.component.idempotency.entity.IdempotencyRecord;

import java.time.Instant;

/**
 * 幂等记录仓储（端口）：sys_idempotency_record 的持久化唯一出入口
 * <p>
 * 组件内其余代码（守卫/僵死清理）一律经本接口访问，**不得越过仓储直接调用 Mapper**；
 * 默认实现 {@link IdempotencyRecordRepository}，宿主可自行实现本接口并注册 Bean 顶替。
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

    /** 清理僵死的处理中记录（早于阈值），返回删除行数 */
    long deleteStaleProcessing(Instant before, int limit);

    /** 清理过期记录（早于阈值），返回删除行数 */
    long deleteExpired(Instant before, int limit);

}
