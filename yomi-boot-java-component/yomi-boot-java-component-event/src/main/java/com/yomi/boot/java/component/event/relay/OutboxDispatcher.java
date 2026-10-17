package com.yomi.boot.java.component.event.relay;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.ds.support.DbDuplicateKey;
import com.yomi.boot.java.component.event.config.EventProperties;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.model.WireEvent;
import com.yomi.boot.java.component.event.repository.IEventOutboxDlqRepository;
import com.yomi.boot.java.component.event.repository.IEventOutboxRepository;
import com.yomi.boot.java.component.kafka.KafkaUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 异步将 FETCHED 发件箱行投递到 Kafka（{@link com.yomi.boot.java.component.kafka.KafkaUtils#sync()}）
 *
 * @author zwei
 */
@Slf4j
public class OutboxDispatcher {

    /** Kafka 已成功但 DB 未标 SENT_SUCCESS 时的回写重试次数 */
    private static final int SENT_SUCCESS_PERSIST_RETRIES = 3;
    /** 发送成功状态落库间隔（毫秒） */
    private static final long SENT_SUCCESS_PERSIST_INTERVAL_MS = 200L;

    /** 发送并行度、Kafka 模板名等 */
    private final EventProperties properties;

    /** 发件箱表 */
    private final IEventOutboxRepository outboxRepository;

    /** 发送失败重试队列 */
    private final IEventOutboxDlqRepository dlqRepository;

    /** 发件箱状态更新事务 */
    private final TransactionTemplate transactionTemplate;

    /** 异步发送 Kafka 的线程池 */
    private final ThreadPoolExecutor executor;

    public OutboxDispatcher(EventProperties properties,
                            IEventOutboxRepository outboxRepository,
                            IEventOutboxDlqRepository dlqRepository,
                            TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.dlqRepository = dlqRepository;
        this.transactionTemplate = transactionTemplate;
        int parallelism = Math.max(1, properties.getDispatchParallelism());
        int queueCapacity = Math.max(1, properties.getDispatchQueueCapacity());
        this.executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                Thread.ofVirtual().factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 提交到发送线程池；队列满时恢复为 PENDING */
    public void offer(EventOutbox row) {
        try {
            executor.execute(() -> runTaskQuietly("发件箱投递", row.getEventId(), () -> dispatch(row)));
        } catch (RejectedExecutionException e) {
            restorePending(row, e);
        }
    }

    /** 主路径发送：成功标 SENT_SUCCESS，失败写 DLQ */
    void dispatch(EventOutbox row) {
        WireEvent wire = new WireEvent(row.getEventId(), row.getTopic(), row.getPayload());
        String json = JsonUtils.toJson(wire);
        try {
            var sender = KafkaUtils.sync().topic(row.getTopic()).key(row.getEventId()).payload(json);
            if (StringUtils.isNotBlank(properties.getKafkaTemplateBeanName())) {
                sender.template(properties.getKafkaTemplateBeanName());
            }
            SendResult<String, Object> result = sender.send();
            log.info("[事件]Kafka 发送成功 eventId={}, topic={}, offset={}",
                    row.getEventId(), row.getTopic(), result.getRecordMetadata().offset());
            markSentSuccess(row);
        } catch (Exception e) {
            log.error("[事件]Kafka 发送失败 eventId={}, topic={}", row.getEventId(), row.getTopic(), e);
            markSentFailAndEnqueueDlq(row, e);
        }
    }

    /** 提交 DLQ 重试到发送线程池，RoundRobin 调度线程随即返回 */
    public void offerFromDlq(EventOutbox outbox, EventOutboxDlq dlq) {
        try {
            executor.execute(() -> runTaskQuietly("DLQ 重试", outbox.getEventId(), () -> dispatchFromDlq(outbox, dlq)));
        } catch (RejectedExecutionException e) {
            log.warn("[事件]DLQ 提交发送队列失败，下轮扫描重试 eventId={}", outbox.getEventId(), e);
        }
    }

    /**
     * DLQ 重试：先取得 in-flight 租约，再同步发送到 Kafka 并更新状态。
     * <p>取得租约后的查行、序列化、发送与回写均在异常保护内；失败时记录日志并推进重试次数与退避，
     * 耗尽后进入 {@code [EXHAUSTED]} 终态</p>
     */
    void dispatchFromDlq(EventOutbox outbox, EventOutboxDlq dlq) {
        if (!dlqRepository.tryClaimInFlight(dlq.getId(), properties.getDlqInFlightLease())) {
            log.debug("[事件]DLQ 已被其他任务抢占或未到重试窗口，跳过 dlqId={}, eventId={}",
                    dlq.getId(), outbox.getEventId());
            return;
        }
        try {
            dispatchClaimedFromDlq(outbox, dlq);
        } catch (Exception e) {
            log.error("[事件]DLQ 重试异常（查行/序列化/回写） dlqId={}, outboxId={}",
                    dlq.getId(), outbox.getId(), e);
            onDlqRetryFailed(dlq, outbox, e);
        }
    }

    /** 取得 DLQ 租约后的查行、发送 Kafka 与状态回写 */
    private void dispatchClaimedFromDlq(EventOutbox scannedOutbox, EventOutboxDlq scannedDlq) {
        EventOutboxDlq dlq = dlqRepository.findById(scannedDlq.getId());
        if (dlq == null) {
            log.warn("[事件]DLQ 行已不存在，跳过重试 dlqId={}, eventId={}",
                    scannedDlq.getId(), scannedOutbox.getEventId());
            return;
        }
        EventOutbox outbox = outboxRepository.findById(scannedOutbox.getId());
        if (outbox == null) {
            // 指针失效（发件箱行已被删除）：与扫描期 OutboxRelayCoordinator.processOneDlq 语义一致，清理 DLQ 行
            log.warn("[事件]DLQ 指针失效（发件箱行不存在），清理 DLQ 行 dlqId={}, outboxId={}",
                    dlq.getId(), scannedOutbox.getId());
            dlqRepository.deleteById(dlq.getId());
            return;
        }
        if (outbox.getStatus() == OutboxStatus.SENT_SUCCESS) {
            log.info("[事件]DLQ 对应发件箱行已发送成功，清理 DLQ 行 dlqId={}, eventId={}",
                    dlq.getId(), outbox.getEventId());
            dlqRepository.deleteById(dlq.getId());
            return;
        }
        WireEvent wire = new WireEvent(outbox.getEventId(), outbox.getTopic(), outbox.getPayload());
        String json = JsonUtils.toJson(wire);
        Instant sentTime = Instant.now();
        try {
            var sender = KafkaUtils.sync().topic(outbox.getTopic()).key(outbox.getEventId()).payload(json);
            if (StringUtils.isNotBlank(properties.getKafkaTemplateBeanName())) {
                sender.template(properties.getKafkaTemplateBeanName());
            }
            sender.send();
            log.info("[事件]DLQ 重试发送成功 eventId={}, topic={}", outbox.getEventId(), outbox.getTopic());
            if (!markDlqSuccessWithRetry(outbox, dlq, sentTime)) {
                log.error("[事件]DLQ 已发送但状态未回写，请人工核查 eventId={}, topic={}",
                        outbox.getEventId(), outbox.getTopic());
            }
        } catch (Exception e) {
            log.error("[事件]DLQ 重试发送失败 eventId={}, topic={}", outbox.getEventId(), outbox.getTopic(), e);
            onDlqRetryFailed(dlq, outbox, e);
        }
    }

    /** 线程池任务包装：捕获未处理异常并记录日志 */
    private static void runTaskQuietly(String stage, String eventId, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("[事件]{}任务出现未捕获异常 stage={}, eventId={}", stage, eventId, e);
        }
    }

    /** 标记 SENT_SUCCESS 状态 */
    private void markSentSuccess(EventOutbox row) {
        Instant sentTime = Instant.now();
        row.setStatus(OutboxStatus.SENT_SUCCESS);
        row.setSentTime(sentTime);
        for (int retry = 1; retry <= SENT_SUCCESS_PERSIST_RETRIES; retry++) {
            try {
                outboxRepository.updateRow(row);
                return;
            } catch (Exception e) {
                log.error("[事件]发送成功但状态回写失败 retry={}, eventId={}", retry, row.getEventId(), e);
                if (retry < SENT_SUCCESS_PERSIST_RETRIES) {
                    sleepQuietly(SENT_SUCCESS_PERSIST_INTERVAL_MS);
                }
            }
        }
        log.error("[事件]已发送成功但状态仍未回写，可能重复发送 eventId={}, topic={}",
                row.getEventId(), row.getTopic());
    }

    /** 事务内标记发件箱成功并删除 DLQ 行 */
    private boolean markDlqSuccessWithRetry(EventOutbox outbox, EventOutboxDlq dlq, Instant sentTime) {
        for (int retry = 1; retry <= SENT_SUCCESS_PERSIST_RETRIES; retry++) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                        dlqRepository.markOutboxSuccessAndDeleteDlq(outbox, dlq, sentTime, outboxRepository));
                return true;
            } catch (Exception e) {
                log.error("[事件]DLQ 成功回写失败 retry={}, eventId={}", retry, outbox.getEventId(), e);
                if (retry < SENT_SUCCESS_PERSIST_RETRIES) {
                    sleepQuietly(SENT_SUCCESS_PERSIST_INTERVAL_MS);
                }
            }
        }
        return false;
    }

    /** 标记发送失败并写入 DLQ 行 */
    private void markSentFailAndEnqueueDlq(EventOutbox row, Exception cause) {
        String errorMsg = cause == null ? "" : cause.getMessage();
        transactionTemplate.executeWithoutResult(status -> {
            row.setStatus(OutboxStatus.SENT_FAIL);
            outboxRepository.updateRow(row);
            EventOutboxDlq existing = dlqRepository.findByEventOutboxId(row.getId());
            if (existing != null) {
                existing.setErrorMsg(errorMsg);
                dlqRepository.updateRow(existing);
                return;
            }
            EventOutboxDlq dlq = new EventOutboxDlq()
                    .setServiceName(row.getServiceName())
                    .setEventOutboxId(row.getId())
                    .setErrorMsg(errorMsg)
                    .setRetryCount(0);
            try {
                dlqRepository.insert(dlq);
            } catch (Exception e) {
                if (DbDuplicateKey.isDuplicateKey(e)) {
                    EventOutboxDlq again = dlqRepository.findByEventOutboxId(row.getId());
                    if (again != null) {
                        again.setErrorMsg(errorMsg);
                        dlqRepository.updateRow(again);
                    }
                    return;
                }
                throw e;
            }
        });
    }

    /** DLQ 重试失败时记录日志并推进退避 */
    private void onDlqRetryFailed(EventOutboxDlq dlq, EventOutbox outbox, Exception cause) {
        int nextCount = dlq.getRetryCount() == null ? 1 : dlq.getRetryCount() + 1;
        dlq.setRetryCount(nextCount);
        Instant now = Instant.now();
        dlq.setRetryLastTime(now);
        dlq.setRetryAfterTime(now.plus(OutboxDlqBackoff.delayAfterFailure(nextCount)));
        if (nextCount >= properties.getDlqMaxRetry()) {
            dlq.setErrorMsg(exhaustedMessage(dlq.getErrorMsg()));
            log.error("[事件]DLQ 重试次数已用尽 eventId={}, outboxId={}, retryCount={}",
                    outbox.getEventId(), outbox.getId(), nextCount);
        }
        try {
            dlqRepository.updateRow(dlq);
        } catch (Exception e) {
            // 回写失败时只记日志：行停在 IN_FLIGHT，租约到期后由下轮扫描继续处理
            log.error("[事件]DLQ 重试计数回写失败 dlqId={}, eventId={}", dlq.getId(), outbox.getEventId(), e);
        }
    }

    /** 构造 DLQ 耗尽终态错误信息 */
    private static String exhaustedMessage(String original) {
        String prefix = "[EXHAUSTED] ";
        if (original != null && original.startsWith(prefix)) {
            return original;
        }
        if (StringUtils.isBlank(original)) {
            return prefix + "已达最大重试次数";
        }
        return prefix + "已达最大重试次数，" + original;
    }

    /** 发送队列满时将发件箱行恢复为 PENDING */
    private void restorePending(EventOutbox row, RejectedExecutionException cause) {
        log.error("[事件]提交发送队列失败，恢复 PENDING eventId={}", row.getEventId(), cause);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                row.setStatus(OutboxStatus.PENDING);
                row.setFetchTime(null);
                outboxRepository.updateRow(row);
            });
        } catch (Exception e) {
            log.error("[事件]恢复 PENDING 失败，依赖 FETCHED 超时回收 eventId={}", row.getEventId(), e);
        }
    }

    /** 休眠指定毫秒并忽略中断 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 停机时等待发送任务结束 */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

}
