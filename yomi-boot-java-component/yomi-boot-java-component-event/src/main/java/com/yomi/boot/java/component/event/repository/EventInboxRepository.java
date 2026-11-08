package com.yomi.boot.java.component.event.repository;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.event.entity.EventInbox;
import com.yomi.boot.java.component.event.repository.mapper.EventInboxMapper;

import java.time.Instant;

/**
 * 收件箱数据访问
 *
 * @author zwei
 */
public class EventInboxRepository implements IEventInboxRepository {

    private final EventInboxMapper mapper;

    public EventInboxRepository(EventInboxMapper mapper) {
        this.mapper = mapper;
    }

    /** lambda 链式条件 */
    private LambdaQueryChainWrapper<EventInbox> query() {
        return ChainWrappers.lambdaQueryChain(mapper, EventInbox.class);
    }

    /** 是否已处理过该 eventId */
    public boolean exists(String serviceName, String eventId) {
        return query()
                .eq(EventInbox::getServiceName, serviceName)
                .eq(EventInbox::getEventId, eventId)
                .isNull(EventInbox::getDeleteTime)
                .exists();
    }

    /** 插入消费凭证 */
    public void insert(EventInbox row) {
        Assert.nonNull(row, "table#EventInbox不能为空");
        Instant now = Instant.now();
        if (row.getId() == null) {
            row.setId(IdUtils.nextId());
        }
        if (row.getCreateTime() == null) {
            row.setCreateTime(now);
        }
        row.setUpdateTime(now);
        mapper.insert(row);
    }

    /** 保留期清理：物理删除指定服务创建时间早于阈值的行 */
    public int deleteCreatedBefore(String serviceName, Instant createTimeBefore, int limit) {
        Assert.isTrue(serviceName != null && !serviceName.isBlank(), "serviceName 不能为空");
        Assert.isTrue(limit > 0, "清理 limit 必须大于 0");
        Assert.nonNull(createTimeBefore, "清理时间阈值不能为空");
        return mapper.deleteCreatedBefore(serviceName, createTimeBefore, limit);
    }

    /** 该服务最旧一行的 create_time（表空返回 null）：走 idx_service_name_create_time */
    public Instant findOldestCreateTime(String serviceName) {
        Assert.isTrue(serviceName != null && !serviceName.isBlank(), "serviceName 不能为空");
        return mapper.selectOldestCreateTime(serviceName);
    }

}
