package com.yomi.boot.java.component.event.repository;

import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.component.ds.MyBatisDao;
import com.yomi.boot.java.component.event.entity.EventInbox;
import com.yomi.boot.java.component.event.repository.mapper.EventInboxMapper;

import java.time.Instant;

/**
 * 收件箱数据访问
 *
 * @author zwei
 */
public class EventInboxRepository extends MyBatisDao<EventInboxMapper, EventInbox>
        implements IEventInboxRepository {

    public EventInboxRepository(EventInboxMapper mapper) {
        super(mapper);
    }

    /** 是否已处理过该 eventId */
    public boolean exists(String serviceName, String eventId) {
        return lambdaQuery()
                .eq(EventInbox::getServiceName, serviceName)
                .eq(EventInbox::getEventId, eventId)
                .isNull(EventInbox::getDeleteTime)
                .exists();
    }

    /** 初始化审计字段并插入消费凭证 */
    public void insert(EventInbox row) {
        row.init();
        save(row);
    }

    /** 保留期清理：物理删除指定服务创建时间早于阈值的行 */
    public int deleteCreatedBefore(String serviceName, Instant createTimeBefore, int limit) {
        Assert.isTrue(serviceName != null && !serviceName.isBlank(), "serviceName 不能为空");
        Assert.isTrue(limit > 0, "清理 limit 必须大于 0");
        Assert.nonNull(createTimeBefore, "清理时间阈值不能为空");
        return getMapper().deleteCreatedBefore(serviceName, createTimeBefore, limit);
    }

    /** 该服务最旧一行的 create_time（表空返回 null）：走 idx_service_name_create_time */
    public Instant findOldestCreateTime(String serviceName) {
        Assert.isTrue(serviceName != null && !serviceName.isBlank(), "serviceName 不能为空");
        return getMapper().selectOldestCreateTime(serviceName);
    }

}
