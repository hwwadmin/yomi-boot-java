package com.yomi.boot.java.component.event.repository;

import com.yomi.boot.java.component.event.entity.EventInbox;

import java.time.Instant;

/**
 * 收件箱仓储（端口）：sys_event_inbox 的持久化唯一出入口
 * <p>
 * 组件内其余代码（消费幂等守卫/清理）经本接口访问，不直接调用 Mapper；
 * 默认实现 {@link EventInboxRepository}，宿主可自行实现本接口并注册自己的 Bean。
 *
 * @author zwei
 */
public interface IEventInboxRepository {

    /** 是否已存在（消费幂等凭证） */
    boolean exists(String serviceName, String eventId);

    /** 写收件箱（消费幂等凭证） */
    void insert(EventInbox row);

    /** 保留期清理：物理删除指定服务且创建时间早于阈值的行 */
    int deleteCreatedBefore(String serviceName, Instant createTimeBefore, int limit);

}
