package com.yomi.boot.java.component.event.publish;

import com.yomi.boot.java.component.event.model.WireEvent;

/**
 * 发件箱发布器：将事件与业务数据在同一事务内落库
 *
 * @author zwei
 */
public interface IEventPublisher {

    /**
     * 写入 {@code sys_event_outbox}，状态 PENDING
     * <p>须在 Spring 事务内调用；{@code (service_name, event_id)} 重复时抛业务异常；
     * 事件序列化后超过 {@code yomi.event.max-payload-bytes} 时抛业务异常（{@code EVENT_TOO_LARGE}），
     * 该事件 Kafka 不会接受，越早失败越好</p>
     */
    void publish(WireEvent event);

}
