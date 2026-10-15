package com.yomi.boot.java.component.event.consume;

import com.yomi.boot.java.component.ds.support.DbDuplicateKey;
import com.yomi.boot.java.component.event.config.EventProperties;
import com.yomi.boot.java.component.event.entity.EventInbox;
import com.yomi.boot.java.component.event.model.WireEvent;
import com.yomi.boot.java.component.event.repository.IEventInboxRepository;
import com.yomi.boot.java.component.event.support.EventKeys;
import com.yomi.boot.java.component.redis.LockUtils;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 收件箱幂等消费：分布式锁 → 事务内写 inbox → 业务处理
 * <p>在 {@code @KafkaListener} 中调用，按 {@link EventConsumeResult} 决定 ack/重投动作。
 * 返回 RETRY 时调用方必须显式退回重投（如 {@code acknowledgment.nack(...)}），
 * 仅不提交 offset 不会重投，消息会被同分区后续提交永久跳过</p>
 * <p>锁只是"省一次写库"的优化，互斥由 inbox 唯一键（insert-first + 重复键兜底）承担：
 * Redis/Redisson 故障时降级为无锁路径（WARN），不让消息在故障期间只能反复退回重投</p>
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class EventInboxGuard {

    /** 消费幂等表 */
    private final IEventInboxRepository inboxRepository;

    /** 服务名等 */
    private final EventKeys eventKeys;
    /** 模块配置 */
    private final EventProperties properties;

    /** 写收件箱与业务处理事务 */
    private final TransactionTemplate transactionTemplate;

    /** 幂等消费，要求消息体含 eventId */
    public EventConsumeResult onMessage(byte[] body, EventHandler handler) {
        return onMessage(WireEventParser.parse(body, true), handler);
    }

    /** 幂等消费，要求消息体含 eventId */
    public EventConsumeResult onMessage(String body, EventHandler handler) {
        return onMessage(WireEventParser.parse(body, true), handler);
    }

    /** 解析 WireEvent 后的幂等消费主流程 */
    private EventConsumeResult onMessage(Optional<WireEvent> parsed, EventHandler handler) {
        if (parsed.isEmpty()) {
            log.warn("[事件]消息解析失败，丢弃");
            return EventConsumeResult.DROP;
        }
        WireEvent evt = parsed.get();
        String lockKey = eventKeys.consumeLockKey(evt.getEventId());
        try {
            return runWithLock(lockKey, () -> runInTransaction(evt, handler));
        } catch (LockBusyException busy) {
            return onLockNotHeld(evt);
        } catch (Exception e) {
            // 锁不可用（Redis/Redisson 故障，经 LockUtils 包装逃逸）：与幂等组件同口径降级为无锁 + inbox 唯一键兜底；
            // 若返回 RETRY，Redis 故障期间所有消息都只能反复退回重投直到 Redis 恢复
            log.warn("[事件]消费锁不可用，降级为无锁 + inbox 唯一键兜底 eventId={}, topic={}",
                    evt.getEventId(), evt.getTopic(), e);
            return runInTransaction(evt, handler);
        }
    }

    /**
     * 非幂等消费：解析失败丢弃；业务异常只打日志，仍返回 SUCCESS
     * <p>带 eventId 时会 WARN，提示改用本类的 {@link #onMessage(String, EventHandler)}</p>
     */
    public EventConsumeResult onMessageNormal(String body, EventHandler handler) {
        var parsed = WireEventParser.parse(body, false);
        if (parsed.isEmpty()) {
            log.warn("[事件]普通消费解析失败，丢弃");
            return EventConsumeResult.DROP;
        }
        WireEvent evt = parsed.get();
        if (evt.getEventId() != null && !evt.getEventId().isBlank()) {
            log.warn("[事件]普通消费检测到 eventId，如需幂等请使用 onMessage eventId={}, topic={}",
                    evt.getEventId(), evt.getTopic());
        }
        try {
            handler.handle(evt);
        } catch (Exception e) {
            log.error("[事件]普通消费业务异常 topic={}", evt.getTopic(), e);
        }
        return EventConsumeResult.SUCCESS;
    }

    /** 在消费分布式锁内执行（包内可见，便于用例编排锁行为：锁忙 vs 锁不可用） */
    EventConsumeResult runWithLock(String lockKey, Supplier<EventConsumeResult> supplier) {
        long waitMs = properties.getConsumeLockWait().toMillis();
        Duration lease = properties.getConsumeLockLease();
        if (lease == null || lease.isZero()) {
            return LockUtils.tryLock(lockKey, waitMs, TimeUnit.MILLISECONDS, supplier, "事件消费抢锁失败");
        }
        return LockUtils.tryLockWithLease(lockKey, waitMs, lease.toMillis(), TimeUnit.MILLISECONDS,
                supplier, "事件消费抢锁失败");
    }

    /** 未抢到消费锁时的处理 */
    private EventConsumeResult onLockNotHeld(WireEvent evt) {
        if (inboxRepository.exists(eventKeys.getServiceName(), evt.getEventId())) {
            log.warn("[事件]重复消息且已入 inbox eventId={}", evt.getEventId());
            return EventConsumeResult.SUCCESS;
        }
        log.warn("[事件]消息处理中，稍后重试 eventId={}", evt.getEventId());
        return EventConsumeResult.RETRY;
    }

    /** 事务内写收件箱并执行业务 */
    private EventConsumeResult runInTransaction(WireEvent evt, EventHandler handler) {
        try {
            Boolean duplicate = transactionTemplate.execute(status -> {
                if (inboxRepository.exists(eventKeys.getServiceName(), evt.getEventId())) {
                    return true;
                }
                EventInbox inbox = new EventInbox()
                        .setServiceName(eventKeys.getServiceName())
                        .setEventId(evt.getEventId())
                        .setTopic(evt.getTopic())
                        .setPayload(evt.getPayload());
                try {
                    inboxRepository.insert(inbox);
                } catch (Exception e) {
                    if (DbDuplicateKey.isDuplicateKey(e)) {
                        return true;
                    }
                    throw e;
                }
                invokeHandler(handler, evt);
                return false;
            });
            if (Boolean.TRUE.equals(duplicate)) {
                log.warn("[事件]重复消息 eventId={}", evt.getEventId());
                return EventConsumeResult.SUCCESS;
            }
            log.info("[事件]消费成功 eventId={}, topic={}", evt.getEventId(), evt.getTopic());
            return EventConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("[事件]消费失败 eventId={}, topic={}", evt.getEventId(), evt.getTopic(), e);
            return EventConsumeResult.RETRY;
        }
    }

    /** 调用业务 EventHandler */
    private static void invokeHandler(EventHandler handler, WireEvent evt) {
        try {
            handler.handle(evt);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(e);
        }
    }

}
