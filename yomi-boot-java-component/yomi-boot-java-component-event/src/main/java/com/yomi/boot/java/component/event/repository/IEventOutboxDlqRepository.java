package com.yomi.boot.java.component.event.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.model.EventDlqQuery;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 发件箱死信仓储（端口）：sys_event_outbox_dlq 的持久化唯一出入口
 * <p>
 * 组件内其余代码（中继/重试/清理）一律经本接口访问，**不得越过仓储直接调用 Mapper**；
 * 默认实现 {@link EventOutboxDlqRepository}，宿主可自行实现本接口并注册 Bean 顶替。
 *
 * @author zwei
 */
public interface IEventOutboxDlqRepository {

    /** 写死信行 */
    void insert(EventOutboxDlq row);

    /** 按主键更新并刷新 update 审计字段 */
    void updateRow(EventOutboxDlq row);

    /** 按主键删除 */
    void deleteById(Long id);

    /** 抢占 in-flight 租约（防并行重试）；返回是否抢占成功 */
    boolean tryClaimInFlight(Long dlqId, Duration lease);

    /** 按发件箱主键查死信行 */
    EventOutboxDlq findByEventOutboxId(Long eventOutboxId);

    /** 按主键查；不存在或已逻辑删除返回 null */
    EventOutboxDlq findById(Long id);

    /** 查询可重试的行（未达最大重试次数且已到重试窗口）；按"就绪时间"（retry_after_time，未排期取 create_time）升序 */
    List<EventOutboxDlq> fetchRetryable(String serviceName, int maxRetry, int limit, Instant now);

    /** 删除指定发件箱行的 DLQ（重发时移交重试责任） */
    int deleteByEventOutboxId(Long eventOutboxId);

    /** 业务查询死信分页（创建时间倒序，走 idx_service_name_create_time 索引） */
    Page<EventOutboxDlq> pageDlq(EventDlqQuery condition, String serviceName);

    /** 重置时间区间 [from, to] 内的重试计数（from/to 不可为空，且 from ≤ to） */
    long resetRetryCountInRange(String serviceName, Instant from, Instant to);

    /** 发件箱标为 SENT_SUCCESS 并删除 DLQ（须在事务内调用） */
    void markOutboxSuccessAndDeleteDlq(EventOutbox outbox, EventOutboxDlq dlq, Instant sentTime,
                                       IEventOutboxRepository outboxRepository);

}
