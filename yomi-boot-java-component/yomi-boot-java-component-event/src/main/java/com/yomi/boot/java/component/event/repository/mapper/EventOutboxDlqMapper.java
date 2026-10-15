package com.yomi.boot.java.component.event.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * sys_event_outbox_dlq 的 Mapper：**仅供 {@link EventOutboxDlqRepository} 使用**
 * （其余代码一律经仓储端口 {@link IEventOutboxDlqRepository} 访问，不直接调用）
 * <p>自定义 SQL 见 {@code mapper/EventOutboxDlqMapper.xml}
 *
 * @author zwei
 */
public interface EventOutboxDlqMapper extends BaseMapper<EventOutboxDlq> {

    /** 未排期（新建/人工复位）且未达最大重试次数：按 create_time 有序，走 idx_service_name_create_time */
    List<EventOutboxDlq> selectRetryableUnscheduled(@Param("serviceName") String serviceName,
                                                    @Param("maxRetry") int maxRetry,
                                                    @Param("limit") int limit);

    /** 已到退避窗口且未达最大重试次数：按 retry_after_time 有序，走 idx_retry_after_time */
    List<EventOutboxDlq> selectRetryableDue(@Param("serviceName") String serviceName,
                                            @Param("maxRetry") int maxRetry,
                                            @Param("now") Instant now,
                                            @Param("limit") int limit);

    /** 人工恢复：指定创建时间范围内清零重试计数 */
    int resetRetryCountInRange(@Param("serviceName") String serviceName,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               @Param("updateTime") Instant updateTime);

}
