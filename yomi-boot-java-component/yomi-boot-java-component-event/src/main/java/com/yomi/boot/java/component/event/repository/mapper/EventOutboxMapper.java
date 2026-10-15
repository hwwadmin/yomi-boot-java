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

    /** 按创建时间升序取 PENDING */
    List<EventOutbox> selectPending(@Param("serviceName") String serviceName,
                                    @Param("limit") int limit);

    /** 乐观：仅当前状态匹配时批量改 FETCHED */
    int markFetched(@Param("ids") List<Long> ids,
                    @Param("fromStatus") OutboxStatus fromStatus,
                    @Param("toStatus") OutboxStatus toStatus,
                    @Param("fetchTime") Instant fetchTime,
                    @Param("updateTime") Instant updateTime);

    /** FETCHED 且抓取时间早于阈值 */
    List<EventOutbox> selectFetchedTimeout(@Param("serviceName") String serviceName,
                                           @Param("status") OutboxStatus status,
                                           @Param("fetchTimeBefore") Instant fetchTimeBefore);

    /** 超时回收：清空 fetch_time 并改回 PENDING */
    int resetFetchedToPending(@Param("ids") List<Long> ids,
                              @Param("status") OutboxStatus status,
                              @Param("toStatus") OutboxStatus toStatus,
                              @Param("updateTime") Instant updateTime);

    /** 保留期清理：物理删除指定服务、指定状态且发送时间早于阈值的行 */
    int deleteSentSuccessBefore(@Param("serviceName") String serviceName,
                                @Param("status") OutboxStatus status,
                                @Param("sentTimeBefore") Instant sentTimeBefore,
                                @Param("limit") int limit);

}
