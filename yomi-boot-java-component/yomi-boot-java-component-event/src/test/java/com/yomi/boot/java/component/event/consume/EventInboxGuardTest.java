package com.yomi.boot.java.component.event.consume;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.component.event.config.EventProperties;
import com.yomi.boot.java.component.event.entity.EventInbox;
import com.yomi.boot.java.component.event.repository.IEventInboxRepository;
import com.yomi.boot.java.component.event.support.EventKeys;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收件箱守卫的锁语义：
 * <ul>
 *   <li>Redis/Redisson 故障（锁不可用）→ 降级为无锁 + inbox 唯一键兜底（WARN），不把消息全卡在 RETRY；</li>
 *   <li>抢锁失败（其他实例在消费）→ 已入 inbox 则 SUCCESS、否则 RETRY（行为不变）；</li>
 *   <li>无锁路径下重复投递仍只消费一次（inbox 行已存在或唯一键冲突都按重复跳过）。</li>
 * </ul>
 */
class EventInboxGuardTest {

    /** Redis 故障：降级为无锁路径后业务照常执行并写入 inbox */
    @Test
    void lockUnavailableDegradesToLockFreeAndStillConsumes() {
        FakeInbox inbox = new FakeInbox();
        List<String> handled = new ArrayList<>();
        EventInboxGuard guard = new TestGuard(inbox.repo(), new IllegalStateException("Redis 连接被拒绝"));

        ListAppender<ILoggingEvent> logs = attachLogs();
        EventConsumeResult result;
        try {
            result = guard.onMessage(body("evt-1"), evt -> handled.add(evt.getEventId()));
        } finally {
            detachLogs(logs);
        }

        assertEquals(EventConsumeResult.SUCCESS, result, "锁不可用必须降级消费，而不是返回 RETRY");
        assertEquals(List.of("evt-1"), handled, "降级路径仍要执行业务");
        assertEquals(1, inbox.inserts.size(), "降级路径仍写 inbox（唯一键才是互斥来源）");
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("降级")),
                "降级必须留下 WARN: " + messages(logs));
    }

    /** 降级路径 + 重复投递：inbox 已有行 → 只跳过，不执行第二遍业务 */
    @Test
    void lockUnavailableWithExistingInboxRowSkipsHandler() {
        FakeInbox inbox = new FakeInbox();
        inbox.rows.add("test|evt-1");
        List<String> handled = new ArrayList<>();
        EventInboxGuard guard = new TestGuard(inbox.repo(), new IllegalStateException("Redis 连接被拒绝"));

        EventConsumeResult result = guard.onMessage(body("evt-1"), evt -> handled.add(evt.getEventId()));

        assertEquals(EventConsumeResult.SUCCESS, result);
        assertEquals(List.of(), handled, "重复消息不得在无锁路径下再执行一次");
        assertEquals(List.of(), inbox.inserts);
    }

    /** 降级路径 + 并发插入撞唯一键：按重复消息处理（异常不得逃逸成 RETRY） */
    @Test
    void lockUnavailableWithUniqueKeyConflictTreatedAsDuplicate() {
        FakeInbox inbox = new FakeInbox();
        inbox.duplicateOnInsert = true;
        List<String> handled = new ArrayList<>();
        EventInboxGuard guard = new TestGuard(inbox.repo(), new IllegalStateException("Redis 连接被拒绝"));

        EventConsumeResult result = guard.onMessage(body("evt-1"), evt -> handled.add(evt.getEventId()));

        assertEquals(EventConsumeResult.SUCCESS, result);
        assertEquals(List.of(), handled, "唯一键冲突方必须放弃业务（互斥由唯一键承担）");
    }

    /** 抢锁失败 + 未入 inbox：说明其他实例正在处理 → RETRY */
    @Test
    void lockBusyWithoutInboxRowReturnsRetry() {
        FakeInbox inbox = new FakeInbox();
        List<String> handled = new ArrayList<>();
        EventInboxGuard guard = new TestGuard(inbox.repo(), new LockBusyException("event:receiver:lock:test:evt-1"));

        EventConsumeResult result = guard.onMessage(body("evt-1"), evt -> handled.add(evt.getEventId()));

        assertEquals(EventConsumeResult.RETRY, result);
        assertEquals(List.of(), handled);
    }

    /** 抢锁失败 + 已入 inbox：重复消息 → SUCCESS（与降级路径口径一致） */
    @Test
    void lockBusyWithInboxRowReturnsSuccess() {
        FakeInbox inbox = new FakeInbox();
        inbox.rows.add("test|evt-1");
        EventInboxGuard guard = new TestGuard(inbox.repo(), new LockBusyException("event:receiver:lock:test:evt-1"));

        assertEquals(EventConsumeResult.SUCCESS, guard.onMessage(body("evt-1"), evt -> {
        }));
    }

    /** 解析失败丢弃（锁不可用的 WARN 不应被误触发） */
    @Test
    void unparsableBodyIsDropped() {
        FakeInbox inbox = new FakeInbox();
        EventInboxGuard guard = new TestGuard(inbox.repo(), new IllegalStateException("Redis 连接被拒绝"));

        assertEquals(EventConsumeResult.DROP, guard.onMessage("not-json", evt -> {
        }));
        assertEquals(List.of(), inbox.inserts);
    }

    // ===== 夹具 =====

    private static String body(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"topic\":\"topic-a\",\"payload\":\"p\"}";
    }

    /** 锁行为可编排的守卫：只覆盖 {@code runWithLock} 这一段（真实实现走 LockUtils + Redis） */
    static class TestGuard extends EventInboxGuard {

        private final RuntimeException lockFailure;

        TestGuard(IEventInboxRepository repository, RuntimeException lockFailure) {
            super(repository, new EventKeys("test"), new EventProperties(),
                    new TransactionTemplate(new FakeTxManager()));
            this.lockFailure = lockFailure;
        }

        @Override
        EventConsumeResult runWithLock(String lockKey, Supplier<EventConsumeResult> supplier) {
            throw lockFailure;
        }
    }

    /** 假收件箱仓储：记录写入并可按 eventId 复用唯一键语义 */
    static class FakeInbox {

        final Set<String> rows = new LinkedHashSet<>();
        final List<String> inserts = new ArrayList<>();
        boolean duplicateOnInsert;

        IEventInboxRepository repo() {
            return (IEventInboxRepository) Proxy.newProxyInstance(
                    IEventInboxRepository.class.getClassLoader(), new Class<?>[]{IEventInboxRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "exists" -> rows.contains(args[0] + "|" + args[1]);
                        case "insert" -> {
                            EventInbox row = (EventInbox) args[0];
                            if (duplicateOnInsert || !rows.add(row.getServiceName() + "|" + row.getEventId())) {
                                throw new DuplicateKeyException("Duplicate entry 'evt-1' for key 'uk_service_event_id'");
                            }
                            inserts.add(row.getEventId());
                            yield null;
                        }
                        case "deleteCreatedBefore" -> 0;
                        default -> null;
                    });
        }
    }

    /** 最小事务管理器：让 transactionTemplate 真进入事务（不连库） */
    static class FakeTxManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(EventInboxGuard.class)).addAppender(appender);
        return appender;
    }

    private static void detachLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(EventInboxGuard.class)).detachAppender(appender);
    }

    private static String messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
    }

}
