package com.yomi.boot.java.component.event.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.model.EventOutboxQuery;

import java.time.Instant;
import java.util.List;

/**
 * 发件箱仓储（端口）：t_sys_event_outbox 的持久化唯一出入口
 * <p>
 * 组件内其余代码（发布/中继/清理）经本接口访问，不直接调用 Mapper；
 * 默认实现 {@link EventOutboxRepository}，宿主可自行实现本接口并注册自己的 Bean。
 *
 * @author zwei
 */
public interface IEventOutboxRepository {

    /** 写发件箱（PENDING） */
    void insert(EventOutbox row);

    /** 查询 PENDING 批量（不改变状态） */
    List<EventOutbox> fetchPending(String serviceName, int limit);

    /** 查询 PENDING 并批量标记 FETCHED；并发下可能部分行已被其他实例抓取 */
    List<EventOutbox> fetchAndMark(String serviceName, int limit);

    /** 查询长时间停留在 FETCHED 的行（回收候选，单批上限 limit，按 id 升序便于分批翻页） */
    List<EventOutbox> findFetchedTimeout(String serviceName, Instant fetchTimeBefore, int limit);

    /** 按 id 批量重置为 PENDING（内部按固定分片下发，单条 IN 的 id 数有上限） */
    void resetFetchedBatch(List<Long> ids);

    /** 按主键更新并刷新 update 审计字段 */
    void updateRow(EventOutbox row);

    /** 按 id 批量查询（过滤逻辑删除） */
    List<EventOutbox> findByIds(List<Long> ids);

    /** 按服务名 + 事件ID 查发件箱行（过滤逻辑删除）；不存在返回 null */
    EventOutbox findByEventId(String serviceName, String eventId);

    /** 按主键查；不存在或已逻辑删除返回 null */
    EventOutbox findById(Long id);

    /** 重发：未送达（非 SENT_SUCCESS）的行重置为 PENDING 并清 fetch_time；返回受影响行数 */
    int resetToPending(String serviceName, String eventId);

    /** 业务查询分页（概要列不含 payload；创建时间倒序，走 idx_service_status_create_time 索引） */
    Page<EventOutbox> pageOutbox(EventOutboxQuery condition, String serviceName);

    /** 保留期清理：物理删除指定服务已成功且发送时间早于阈值的行 */
    int deleteSentSuccessBefore(String serviceName, Instant sentTimeBefore, int limit);

    /** 该服务最旧一条已成功行的 {@code sent_time}（无则 null）：保留期清理的落后度量 */
    Instant findOldestSentSuccessTime(String serviceName);

}
