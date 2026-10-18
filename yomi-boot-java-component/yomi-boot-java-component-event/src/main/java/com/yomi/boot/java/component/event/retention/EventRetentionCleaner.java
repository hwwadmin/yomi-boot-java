package com.yomi.boot.java.component.event.retention;

import com.yomi.boot.java.component.event.config.EventProperties;
import com.yomi.boot.java.component.event.repository.IEventInboxRepository;
import com.yomi.boot.java.component.event.repository.IEventOutboxRepository;
import com.yomi.boot.java.component.event.support.EventKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * 保留期清理（默认关闭）：按保留期物理删除历史行，防止表只增不删无限膨胀。
 * <p>发布侧只删 {@code sys_event_outbox} 中 {@code SENT_SUCCESS} 且发送时间超过保留期的行——
 * PENDING/FETCHED/SENT_FAIL 以及 DLQ 仍在引用的行不受影响；
 * 消费侧删 {@code sys_event_inbox} 中创建时间超过保留期的行，会缩短"只消费一次"的判重窗口，
 * 需单独开启 {@code inbox-retention-enabled} 并自行保证保留期 ≥ 事件可能被重投的最长时间。</p>
 * 两侧各自实例化：发布侧只传发件箱仓储、消费侧只传收件箱仓储，未传入的一侧不清理。
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class EventRetentionCleaner {

    /** 发件箱清理任务名（集群定时任务名与锁键） */
    public static final String TASK_NAME_OUTBOX = "eventOutboxRetentionCleaner";
    /** 收件箱清理任务名（集群定时任务名与锁键） */
    public static final String TASK_NAME_INBOX = "eventInboxRetentionCleaner";

    /** 模块配置 */
    private final EventProperties properties;

    /** 当前实例归属服务名（与发件箱/收件箱行一致） */
    private final EventKeys eventKeys;

    /** 发布侧传入，消费侧为 null */
    private final IEventOutboxRepository outboxRepository;

    /** 消费侧传入，发布侧为 null */
    private final IEventInboxRepository inboxRepository;

    /** 发布侧任务：清理已成功且超过保留期的发件箱行 */
    public void cleanOutbox() {
        if (outboxRepository == null) {
            return;
        }
        EventProperties.Retention retention = properties.getRetention();
        Instant before = Instant.now().minus(retention.getOutboxRetention());
        String serviceName = eventKeys.getServiceName();
        long deleted = deleteInBatches("sys_event_outbox",
                () -> outboxRepository.deleteSentSuccessBefore(serviceName, before, retention.getBatchSize()),
                retention);
        if (deleted > 0) {
            log.info("[事件]保留期清理 sys_event_outbox(SENT_SUCCESS) deleted={}, retention={}, before={}",
                    deleted, retention.getOutboxRetention(), before);
        }
    }

    /** 消费侧任务：清理超过保留期的收件箱行 */
    public void cleanInbox() {
        if (inboxRepository == null) {
            return;
        }
        EventProperties.Retention retention = properties.getRetention();
        Instant before = Instant.now().minus(retention.getInboxRetention());
        String serviceName = eventKeys.getServiceName();
        long deleted = deleteInBatches("sys_event_inbox",
                () -> inboxRepository.deleteCreatedBefore(serviceName, before, retention.getBatchSize()),
                retention);
        if (deleted > 0) {
            log.info("[事件]保留期清理 sys_event_inbox deleted={}, retention={}, before={}",
                    deleted, retention.getInboxRetention(), before);
        }
    }

    /** 分批删除直到删空或达到单轮上限，单轮占用数据库的时间有上限 */
    private static long deleteInBatches(String table, IntSupplier batchDelete, EventProperties.Retention retention) {
        long total = 0;
        while (total < retention.getMaxDeletePerRun()) {
            int deleted = batchDelete.getAsInt();
            total += deleted;
            if (deleted < retention.getBatchSize()) {
                return total;
            }
        }
        log.warn("[事件]保留期清理 {} 达到单轮上限 maxDeletePerRun={}，剩余行下轮继续", table,
                retention.getMaxDeletePerRun());
        return total;
    }

}
