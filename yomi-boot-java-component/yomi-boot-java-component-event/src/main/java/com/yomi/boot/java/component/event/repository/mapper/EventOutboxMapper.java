package com.yomi.boot.java.component.event.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * sys_event_outbox 的 Mapper：**仅供 {@link EventOutboxRepository} 使用**
 * （其余代码一律经仓储端口 {@link IEventOutboxRepository} 访问，不直接调用）
 * <p>自定义 SQL 见 {@code mapper/EventOutboxMapper.xml}
 *
 * @author zwei
 */
public interface EventOutboxMapper extends BaseMapper<EventOutbox> {

    /** 保留期清理：物理删除指定服务、指定状态且发送时间早于阈值的行 */
    int deleteSentSuccessBefore(@Param("serviceName") String serviceName,
                                @Param("status") OutboxStatus status,
                                @Param("sentTimeBefore") Instant sentTimeBefore,
                                @Param("limit") int limit);

}
