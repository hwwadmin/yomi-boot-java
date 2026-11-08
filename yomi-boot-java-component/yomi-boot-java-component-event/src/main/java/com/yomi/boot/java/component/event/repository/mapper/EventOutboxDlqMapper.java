package com.yomi.boot.java.component.event.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * t_sys_event_outbox_dlq 的 Mapper：仅供 {@link EventOutboxDlqRepository} 使用
 * （其余代码经仓储端口 {@link IEventOutboxDlqRepository} 访问，不直接调用）
 * <p>自定义 SQL 见 {@code mapper/EventOutboxDlqMapper.xml}
 *
 * @author zwei
 */
public interface EventOutboxDlqMapper extends BaseMapper<EventOutboxDlq> {

    /** 人工恢复：指定创建时间范围内清零重试计数 */
    int resetRetryCountInRange(@Param("serviceName") String serviceName,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               @Param("updateTime") Instant updateTime);

}
