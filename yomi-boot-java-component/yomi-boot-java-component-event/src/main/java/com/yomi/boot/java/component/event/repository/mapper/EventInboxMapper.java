package com.yomi.boot.java.component.event.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.event.entity.EventInbox;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * t_sys_event_inbox 的 Mapper：仅供 {@link EventInboxRepository} 使用
 * （其余代码经仓储端口 {@link IEventInboxRepository} 访问，不直接调用）
 * <p>CRUD 由 MyBatis-Plus 提供，保留期清理见 {@code mapper/EventInboxMapper.xml}
 *
 * @author zwei
 */
public interface EventInboxMapper extends BaseMapper<EventInbox> {

    /** 保留期清理：物理删除指定服务且创建时间早于阈值的行 */
    int deleteCreatedBefore(@Param("serviceName") String serviceName,
                            @Param("createTimeBefore") Instant createTimeBefore,
                            @Param("limit") int limit);

    /** 指定服务最旧一行的 create_time（表空返回 NULL）：清理落后度量 */
    Instant selectOldestCreateTime(@Param("serviceName") String serviceName);

}
