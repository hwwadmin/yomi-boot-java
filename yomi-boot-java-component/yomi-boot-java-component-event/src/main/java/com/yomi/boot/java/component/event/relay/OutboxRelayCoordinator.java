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

    /** 抓取任务名前缀，完整注册名为 {@code 前缀 + "_" + serviceName} */
    public static final String TASK_FETCH = "eventOutboxFetch";
    /** FETCHED 超时回收任务名前缀 */
    public static final String TASK_FETCH_TIMEOUT = "eventOutboxFetchTimeout";
    /** DLQ 重试任务名前缀 */
    public static final String TASK_DLQ = "eventDlqRetry";

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

    /**
     * 将长时间停留在 FETCHED 的行重置为 PENDING
     * <p>按批回收：Kafka 长时间不可用时 FETCHED 行会持续累积（每轮抓取 fetchBatchSize 行），
     * 一次性加载全部行并把等量 id 放进单条 UPDATE 会超过绑定参数上限（PG 65535）
     */
    public void recoverFetchedTimeout(RoundRobinContext ctx) {
        Instant before = Instant.now().minus(properties.getFetchedTimeout());
        String serviceName = eventKeys.getServiceName();
        int batchSize = properties.getFetchBatchSize();
        int recovered = 0;
        while (!ctx.isCancelled()) {
            List<EventOutbox> rows = outboxRepository.findFetchedTimeout(serviceName, before, batchSize);
            if (rows.isEmpty()) {
                break;
            }
            outboxRepository.resetFetchedBatch(rows.stream().map(EventOutbox::getId).toList());
            recovered += rows.size();
            if (rows.size() < batchSize) {
                break;
            }
        }
        if (recovered > 0) {
            log.warn("[事件]FETCHED 超时回收 count={}, service={}", recovered, serviceName);
        }
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
