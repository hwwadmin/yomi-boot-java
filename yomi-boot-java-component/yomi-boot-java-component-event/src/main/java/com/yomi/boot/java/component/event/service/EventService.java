package com.yomi.boot.java.component.event.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageResponse;
import com.yomi.boot.java.component.event.IEventService;
import com.yomi.boot.java.component.event.convert.EventModelConverter;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import com.yomi.boot.java.component.event.model.EventDlqModel;
import com.yomi.boot.java.component.event.model.EventDlqQuery;
import com.yomi.boot.java.component.event.model.EventOutboxModel;
import com.yomi.boot.java.component.event.model.EventOutboxQuery;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.repository.IEventInboxRepository;
import com.yomi.boot.java.component.event.repository.IEventOutboxDlqRepository;
import com.yomi.boot.java.component.event.repository.IEventOutboxRepository;
import com.yomi.boot.java.component.event.support.EventKeys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 事件查询与运维服务默认实现（{@link IEventService}）：数据统一按当前服务隔离
 * <p>依赖按需获取：查询/重发需 publisher-enabled（发件箱仓储），消费凭证需 consumer-enabled（收件箱仓储）；
 * 未启用的能力调用时抛出带配置指引的异常，而不是返回误导性的空结果。
 *
 * @author zwei
 */
public class EventService implements IEventService {

    /** 发件箱仓储（publisher-enabled 时可用） */
    private final ObjectProvider<IEventOutboxRepository> outboxProvider;

    /** DLQ 仓储（publisher-enabled 时可用） */
    private final ObjectProvider<IEventOutboxDlqRepository> dlqProvider;

    /** 收件箱仓储（consumer-enabled 时可用） */
    private final ObjectProvider<IEventInboxRepository> inboxProvider;

    /** 当前服务名等隔离键 */
    private final EventKeys keys;

    /** 重发两步（删 DLQ 行 + 重置 PENDING）的事务边界 */
    private final TransactionTemplate transactionTemplate;

    public EventService(ObjectProvider<IEventOutboxRepository> outboxProvider,
                        ObjectProvider<IEventOutboxDlqRepository> dlqProvider,
                        ObjectProvider<IEventInboxRepository> inboxProvider,
                        EventKeys keys,
                        TransactionTemplate transactionTemplate) {
        this.outboxProvider = outboxProvider;
        this.dlqProvider = dlqProvider;
        this.inboxProvider = inboxProvider;
        this.keys = keys;
        this.transactionTemplate = transactionTemplate;
    }

    /** 查询发件箱事件状态 */
    @Override
    public OutboxStatus outboxStatus(String eventId) {
        EventOutbox row = outbox().findByEventId(keys.getServiceName(), eventId);
        return row == null ? null : row.getStatus();
    }

    /** 按 eventId 查询发件箱详情 */
    @Override
    public EventOutboxModel findOutbox(String eventId) {
        EventOutbox row = outbox().findByEventId(keys.getServiceName(), eventId);
        return row == null ? null : EventModelConverter.toOutboxModel(row);
    }

    /** 分页查询发件箱（限定当前服务） */
    @Override
    public PageResponse<EventOutboxModel> pageOutbox(EventOutboxQuery query) {
        EventOutboxQuery condition = query == null ? new EventOutboxQuery() : query;
        Page<EventOutbox> result = outbox().pageOutbox(condition, keys.getServiceName());
        return PageResponse.page(result.getRecords().stream().map(EventModelConverter::toOutboxModel).toList(),
                result.getTotal());
    }

    /** 收件箱是否已存在消费凭证 */
    @Override
    public boolean consumed(String eventId) {
        return inbox().exists(keys.getServiceName(), eventId);
    }

    /** 分页查询 DLQ 记录 */
    @Override
    public PageResponse<EventDlqModel> pageDlq(EventDlqQuery query) {
        EventDlqQuery condition = query == null ? new EventDlqQuery() : query;
        Page<EventOutboxDlq> result = dlq().pageDlq(condition, keys.getServiceName());
        List<EventDlqModel> models = result.getRecords().stream().map(EventModelConverter::toDlqModel).toList();
        fillEventIds(models);
        return PageResponse.page(models, result.getTotal());
    }

    /** 将失败发件重置为 PENDING 并清理 DLQ 行（事务内两步） */
    @Override
    public boolean requeue(String eventId) {
        IEventOutboxRepository outboxRepository = outbox();
        EventOutbox row = outboxRepository.findByEventId(keys.getServiceName(), eventId);
        if (row == null || row.getStatus() == OutboxStatus.SENT_SUCCESS) {
            return false;
        }
        // 两步必须同成同败：单独执行时若在中间崩溃/第二步失败，"DLQ 行已删、行还停在 SENT_FAIL"
        // 会让这条事件永不再投（中继只抓 PENDING、DLQ 扫描已无行），只能人工再 requeue 一次。
        // 顺序保持：先删 DLQ 行（阻断既有 DLQ 重试路径，其重试前会重查 DLQ 行），再重置 PENDING。
        Boolean requeued = transactionTemplate.execute(status -> {
            IEventOutboxDlqRepository dlqRepository = dlqProvider.getIfAvailable();
            if (dlqRepository != null) {
                dlqRepository.deleteByEventOutboxId(row.getId());
            }
            return outboxRepository.resetToPending(keys.getServiceName(), eventId) > 0;
        });
        return Boolean.TRUE.equals(requeued);
    }

    /** 批量重置时间范围内 DLQ 的重试计数 */
    @Override
    public long resetDlqRetryCount(Instant from, Instant to) {
        return dlq().resetRetryCountInRange(keys.getServiceName(), from, to);
    }

    /** 批量补全 DLQ 关联的 eventId（一批一次查询，避免逐行 N+1） */
    private void fillEventIds(List<EventDlqModel> models) {
        if (models.isEmpty()) {
            return;
        }
        List<Long> outboxIds = models.stream().map(EventDlqModel::getEventOutboxId).distinct().toList();
        Map<Long, String> eventIdByOutboxId = outbox().findByIds(outboxIds).stream()
                .collect(Collectors.toMap(EventOutbox::getId, EventOutbox::getEventId, (a, b) -> a));
        models.forEach(model -> model.setEventId(eventIdByOutboxId.get(model.getEventOutboxId())));
    }

    /** 懒解析发件箱仓储，未启用发布能力时抛错 */
    private IEventOutboxRepository outbox() {
        return require(outboxProvider.getIfAvailable(),
                "yomi.event.publisher-enabled=true（发件箱查询/重发）");
    }

    /** 懒解析 DLQ 仓储，未启用发布能力时抛错 */
    private IEventOutboxDlqRepository dlq() {
        return require(dlqProvider.getIfAvailable(),
                "yomi.event.publisher-enabled=true（死信查询/重试计数重置）");
    }

    /** 懒解析收件箱仓储，未启用消费能力时抛错 */
    private IEventInboxRepository inbox() {
        return require(inboxProvider.getIfAvailable(),
                "yomi.event.consumer-enabled=true（消费凭证查询）");
    }

    /** 仓储 Bean 缺失时抛出带启用提示的 {@link IllegalStateException} */
    private static <T> T require(T repository, String requirement) {
        if (repository == null) {
            throw new IllegalStateException("[事件]该能力需要启用: " + requirement);
        }
        return repository;
    }

}
