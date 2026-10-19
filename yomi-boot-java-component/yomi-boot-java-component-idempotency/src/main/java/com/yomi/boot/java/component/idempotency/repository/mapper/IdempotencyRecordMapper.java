package com.yomi.boot.java.component.idempotency.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.idempotency.entity.IdempotencyRecord;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * sys_idempotency_record 的 Mapper：仅供 {@link IdempotencyRecordRepository} 使用
 * （其余代码经仓储端口 {@link IIdempotencyRecordRepository} 访问，不直接调用）
 * <p>增删改查 MP 自带，自定义 SQL 见 {@code mapper/IdempotencyRecordMapper.xml}
 *
 * @author zwei
 */
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {

    /** 表内最小的 expire_time（表空返回 NULL） */
    Instant selectOldestExpireTime();

    /** 物理删除超时仍处于处理中的记录 */
    int deleteStaleProcessing(@Param("processingStatus") int processingStatus,
                              @Param("before") Instant before,
                              @Param("limit") int limit);

    /** 物理删除已过期的记录 */
    int deleteExpired(@Param("before") Instant before, @Param("limit") int limit);

}
