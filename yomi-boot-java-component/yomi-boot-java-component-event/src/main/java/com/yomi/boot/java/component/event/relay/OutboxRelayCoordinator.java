package com.yomi.boot.java.component.event.relay;

import com.yomi.boot.java.component.event.config.EventProperties;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.repository.IEventOutboxDlqRepository;
import com.yomi.boot.java.component.event.repository.IEventOutboxRepository;
import com.yomi.boot.java.component.event.support.EventKeys;
import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 发件箱后台任务：抓取、超时回收、DLQ 重试（由 RoundRobin 调度）
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxRelayCoordinator {

    /** RoundRobin 任务名前缀，完整名为 {@code 前缀 + "_" + serviceName} */
    public static final String TASK_FETCH = "MqIEventPublisherFetch";
    /** 出站拉取任务名 */
    public static final String TASK_FETCH_TIMEOUT = "MqIEventPublisherFetchTimeout";
    /** DLQ 重试任务名 */
    public static final String TASK_DLQ = "MqIEventPublisherDlq";

    /** 事件模块配置 */
    private final EventProperties properties;

    /** 当前服务名与 Topic 等键 */
    private final EventKeys eventKeys;

    /** 发件箱表 */
    private final IEventOutboxRepository outboxRepository;

    /** DLQ 表 */
    private final IEventOutboxDlqRepository dlqRepository;

    /** Kafka 异步发送 */
    private final OutboxDispatcher dispatcher;

    /** 抓取 PENDING → FETCHED 并提交发送队列 */
    public void fetchAndDispatch(RoundRobinContext ctx) {
        String serviceName = eventKeys.getServiceName();
        List<EventOutbox> rows = outboxRepository.fetchAndMark(serviceName, properties.getFetchBatchSize());
        if (rows.isEmpty()) {
            return;
        }
        for (EventOutbox row : rows) {
            dispatcher.offer(row);
        }
    }

    /** 将长时间停留在 FETCHED 的行重置为 PENDING */
    public void recoverFetchedTimeout(RoundRobinContext ctx) {
        Instant before = Instant.now().minus(properties.getFetchedTimeout());
        List<EventOutbox> rows = outboxRepository.findFetchedTimeout(eventKeys.getServiceName(), before);
        if (rows.isEmpty()) {
            return;
        }
        log.warn("[事件]FETCHED 超时回收 count={}, service={}", rows.size(), eventKeys.getServiceName());
        List<Long> ids = rows.stream().map(EventOutbox::getId).toList();
        outboxRepository.resetFetchedBatch(ids);
    }

    /** 扫描 DLQ 并重试 Kafka 发送 */
    public void processDlq(RoundRobinContext ctx) {
        int maxRetry = properties.getDlqMaxRetry();
        if (maxRetry <= 0) {
            return;
        }
        List<EventOutboxDlq> entries = dlqRepository.fetchRetryable(
                eventKeys.getServiceName(), maxRetry, properties.getDlqBatchSize(), Instant.now());
        if (entries.isEmpty()) {
            return;
        }
        List<Long> outboxIds = entries.stream().map(EventOutboxDlq::getEventOutboxId).toList();
        Map<Long, EventOutbox> outboxMap = outboxRepository.findByIds(outboxIds).stream()
                .collect(Collectors.toMap(EventOutbox::getId, Function.identity(), (a, b) -> a));
        for (EventOutboxDlq dlq : entries) {
            EventOutbox outbox = outboxMap.get(dlq.getEventOutboxId());
            processOneDlq(dlq, outbox);
        }
    }

    /** 处理单条 DLQ 重试 */
    private void processOneDlq(EventOutboxDlq dlq, EventOutbox outbox) {
        if (outbox == null) {
            dlqRepository.deleteById(dlq.getId());
            return;
        }
        if (outbox.getStatus() == OutboxStatus.SENT_SUCCESS) {
            dlqRepository.deleteById(dlq.getId());
            return;
        }
        if (dlq.getRetryCount() != null && dlq.getRetryCount() >= properties.getDlqMaxRetry()) {
            return;
        }
        dispatcher.offerFromDlq(outbox, dlq);
    }

}
