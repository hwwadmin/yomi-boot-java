package com.yomi.boot.java.component.event;

import com.yomi.boot.java.base.model.PageResponse;
import com.yomi.boot.java.component.event.model.EventDlqModel;
import com.yomi.boot.java.component.event.model.EventDlqQuery;
import com.yomi.boot.java.component.event.model.EventOutboxModel;
import com.yomi.boot.java.component.event.model.EventOutboxQuery;
import com.yomi.boot.java.component.event.model.OutboxStatus;

import java.time.Instant;

/**
 * 事件查询与运维服务（业务系统接入用）
 * <p>
 * 由自动装配提供默认实现（{@code service.EventService}），宿主可自行实现本接口并注册自己的 Bean。
 * <p>契约：
 * <ul>
 *     <li>全部数据按当前服务（{@code yomi.event.service-name}）隔离，不提供跨服务访问；</li>
 *     <li>{@link #pageOutbox} 返回概要字段（不含 payload），{@link #findOutbox} 返回全量；</li>
 *     <li>{@link #requeue} 为至少一次语义下的重投：未送达（未 SENT_SUCCESS）的事件重置为 PENDING
 *     并清除其 DLQ 重试责任，由中继重新投递；已送达事件拒绝重发（如需重发请以新事件发布）；
 *     极端并发窗口下可能与中继重试路径重复投递一次，消费端由收件箱幂等处理。</li>
 * </ul>
 *
 * @author zwei
 */
public interface IEventService {

    /**
     * 事件当前状态（发件箱）
     *
     * @return 不存在返回 null
     */
    OutboxStatus outboxStatus(String eventId);

    /**
     * 发件箱详情（含 payload）
     *
     * @return 不存在返回 null
     */
    EventOutboxModel findOutbox(String eventId);

    /** 发件箱分页（概要字段，创建时间倒序；条件可空） */
    PageResponse<EventOutboxModel> pageOutbox(EventOutboxQuery query);

    /** 该事件是否已被本服务消费（收件箱幂等凭证；需 consumer-enabled） */
    boolean consumed(String eventId);

    /** 发件箱死信分页（含关联 eventId 与失败原因/重试次数，创建时间倒序） */
    PageResponse<EventDlqModel> pageDlq(EventDlqQuery query);

    /**
     * 重发未送达的事件：重置为 PENDING 并删除其 DLQ 行，由中继重新投递
     * <p>两步在同一事务内完成（失败整体回滚），不会留下"DLQ 行已删、行未重置"的永久不投状态</p>
     *
     * @return 是否已重置（false=事件不存在或已 SENT_SUCCESS）
     */
    boolean requeue(String eventId);

    /** 将创建时间在 [from, to] 内的 DLQ 重试计数清零（人工恢复自动重试） */
    long resetDlqRetryCount(Instant from, Instant to);

}
