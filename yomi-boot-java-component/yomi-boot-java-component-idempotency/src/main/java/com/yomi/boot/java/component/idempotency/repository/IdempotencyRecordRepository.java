package com.yomi.boot.java.component.idempotency.repository;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.component.ds.MyBatisDao;
import com.yomi.boot.java.component.idempotency.core.IdempotencyStatus;
import com.yomi.boot.java.component.idempotency.entity.IdempotencyRecord;
import com.yomi.boot.java.component.idempotency.repository.mapper.IdempotencyRecordMapper;

import java.time.Instant;

/**
 * sys_idempotency_record 数据访问（继承 MyBatisDao，通用 CRUD 由 MP 提供）
 *
 * @author zwei
 */
public class IdempotencyRecordRepository extends MyBatisDao<IdempotencyRecordMapper, IdempotencyRecord>
        implements IIdempotencyRecordRepository {

    public IdempotencyRecordRepository(IdempotencyRecordMapper mapper) {
        super(mapper);
    }

    /** 按命名空间 + 幂等键查询未软删记录 */
    public IdempotencyRecord findByNamespaceAndKey(String namespace, String idempotencyKey) {
        return lambdaQuery()
                .eq(IdempotencyRecord::getNamespace, namespace)
                .eq(IdempotencyRecord::getIdempotencyKey, idempotencyKey)
                .isNull(IdempotencyRecord::getDeleteTime)
                .one();
    }

    /** 业务成功后更新状态与结果 */
    public void markCompleted(Long id, String resultType, String resultPayload) {
        Assert.nonNull(id, "幂等记录ID不能为空");
        // 这里要用行数判断更新是否生效，因此直接调 mapper.update(null, wrapper)（lambdaUpdate() 链只返回 boolean）
        int rows = getMapper().update(null, new LambdaUpdateWrapper<IdempotencyRecord>()
                .set(IdempotencyRecord::getStatus, IdempotencyStatus.COMPLETED)
                .set(IdempotencyRecord::getResultType, resultType)
                .set(IdempotencyRecord::getResultPayload, resultPayload)
                .set(IdempotencyRecord::getUpdateTime, Instant.now())
                .eq(IdempotencyRecord::getId, id)
                .eq(IdempotencyRecord::getStatus, IdempotencyStatus.PROCESSING)
                .isNull(IdempotencyRecord::getDeleteTime));
        Assert.isTrue(rows == 1, "幂等记录状态更新未生效");
    }

    /** 表内最小的 expire_time（表空返回 null）：走 idx_expire_time 的 MIN 优化 */
    public Instant findOldestExpireTime() {
        return getMapper().selectOldestExpireTime();
    }

    /** 清理僵尸处理中记录 */
    public long deleteStaleProcessing(Instant before, int limit) {
        Assert.isTrue(limit > 0, "清理批量大小必须大于 0");
        return getMapper().deleteStaleProcessing(IdempotencyStatus.PROCESSING, before, limit);
    }

    /** 清理已过 expire_time 的记录 */
    public long deleteExpired(Instant before, int limit) {
        Assert.isTrue(limit > 0, "清理批量大小必须大于 0");
        return getMapper().deleteExpired(before, limit);
    }

}
