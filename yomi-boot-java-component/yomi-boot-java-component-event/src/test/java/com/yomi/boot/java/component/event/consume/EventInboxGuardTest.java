package com.yomi.boot.java.component.event.consume;

import ch.qos.logback.classic.Level;
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
import org.springframework.transaction.SavepointManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收件箱守卫语义：
 * <ul>
 *   <li>锁语义：Redis/Redisson 故障（锁不可用）→ 走无锁路径 + inbox 唯一键保证互斥（WARN），消息照常消费；</li>
 *   <li>锁被其他实例占用 → 已入 inbox 则 SUCCESS，未入则 RETRY；</li>
 *   <li>无锁路径下重复投递仍只消费一次（inbox 行已存在或唯一键冲突都按重复跳过）；</li>
 *   <li>普通路径（onMessageNormal）不写 inbox：解析失败 DROP、业务异常仅日志仍 SUCCESS
 *       （失败不重投，仅用于可丢弃或可补偿场景），带 eventId 必有 WARN。</li>
 * </ul>
 */
class EventInboxGuardTest {

    /** Redis 故障：走无锁路径后业务照常执行并写入 inbox */
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

    /** 无锁路径 + 重复投递：inbox 已有行 → 只跳过，不执行第二遍业务 */
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

    /** 无锁路径 + 并发插入撞唯一键：按重复消息处理（异常不变成 RETRY），并回滚到保存点 */
    @Test
    void lockUnavailableWithUniqueKeyConflictTreatedAsDuplicate() {
        FakeInbox inbox = new FakeInbox();
        inbox.duplicateOnInsert = true;
        SavepointTxManager txManager = new SavepointTxManager();
        List<String> handled = new ArrayList<>();
        EventInboxGuard guard = new TestGuard(inbox.repo(), new IllegalStateException("Redis 连接被拒绝"), txManager);

        EventConsumeResult result = guard.onMessage(body("evt-1"), evt -> handled.add(evt.getEventId()));

        assertEquals(EventConsumeResult.SUCCESS, result);
        assertEquals(List.of(), handled, "唯一键冲突方必须放弃业务（互斥由唯一键承担）");
        assertEquals(1, txManager.savepoints.created.get(), "冲突前必须建保存点");
        assertEquals(1, txManager.savepoints.rolledBack.get(),
                "冲突后必须回滚到保存点：PG 下不回滚会让事务保持 aborted，提交会静默变成整体回滚");
        assertEquals(1, txManager.savepoints.released.get(), "保存点必须释放，避免长事务内堆积");
        assertEquals(1, txManager.commits.get(), "重复消息应正常提交（保存点回滚后事务仍可用）");
        assertEquals(0, txManager.rollbacks.get(), "不应走整体回滚");
    }

    /** 锁被占用 + 未入 inbox：说明其他实例正在处理 → RETRY */
    @Test
    void lockBusyWithoutInboxRowReturnsRetry() {
        FakeInbox inbox = new FakeInbox();
        List<String> handled = new ArrayList<>();
        EventInboxGuard guard = new TestGuard(inbox.repo(), new LockBusyException("event:receiver:lock:test:evt-1"));

        EventConsumeResult result = guard.onMessage(body("evt-1"), evt -> handled.add(evt.getEventId()));

        assertEquals(EventConsumeResult.RETRY, result);
        assertEquals(List.of(), handled);
    }

    /** 锁被占用 + 已入 inbox：重复消息 → SUCCESS（与无锁路径口径一致） */
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

    // ===== 普通路径（onMessageNormal）：不写 inbox，失败仅日志不重投 =====

    /** 业务异常只记 ERROR，仍返回 SUCCESS；不写 inbox（失败不重投，仅用于可丢弃或可补偿场景） */
    @Test
    void normalPathHandlerFailureIsLoggedAndReturnsSuccess() {
        FakeInbox inbox = new FakeInbox();
        EventInboxGuard guard = new EventInboxGuard(inbox.repo(), new EventKeys("test"), new EventProperties(), null);
        AtomicInteger invocations = new AtomicInteger();

        ListAppender<ILoggingEvent> logs = attachLogs();
        EventConsumeResult result;
        try {
            result = guard.onMessageNormal(body("evt-1"), evt -> {
                invocations.incrementAndGet();
                throw new IllegalStateException("业务失败");
            });
        } finally {
            detachLogs(logs);
        }

        assertEquals(EventConsumeResult.SUCCESS, result, "普通路径失败不返回 RETRY：无 inbox 兜底，重投可能重复副作用");
        assertEquals(1, invocations.get(), "handler 必须被调用一次且异常不逃逸");
        assertEquals(List.of(), inbox.inserts, "普通路径不写 inbox");
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("普通消费业务异常")), "失败必须留 ERROR: " + messages(logs));
    }

    /** 带 eventId 的普通消费会 WARN 点名 onMessage（幂等路径的运行时提示） */
    @Test
    void normalPathWarnsOnEventId() {
        EventInboxGuard guard = normalGuard();
        List<String> handled = new ArrayList<>();

        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            assertEquals(EventConsumeResult.SUCCESS,
                    guard.onMessageNormal(body("evt-1"), evt -> handled.add(evt.getEventId())));
        } finally {
            detachLogs(logs);
        }

        assertEquals(List.of("evt-1"), handled, "WARN 不改变执行，只提示改用 onMessage");
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("onMessage")), "带 eventId 必须 WARN 点名 onMessage: " + messages(logs));
    }

    /** 普通路径解析失败丢弃，不执行 handler */
    @Test
    void normalPathDropsUnparsableBody() {
        EventInboxGuard guard = normalGuard();
        AtomicInteger invocations = new AtomicInteger();

        assertEquals(EventConsumeResult.DROP, guard.onMessageNormal("not-json", evt -> invocations.incrementAndGet()));
        assertEquals(0, invocations.get());
    }

    // ===== 夹具 =====

    /** 普通路径不碰 inbox/锁/事务，仓储与事务模板传 null 即可 */
    private static EventInboxGuard normalGuard() {
        return new EventInboxGuard(null, new EventKeys("test"), new EventProperties(), null);
    }

    private static String body(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"topic\":\"topic-a\",\"payload\":\"p\"}";
    }

    /** 锁行为可编排的守卫：只覆盖 {@code runWithLock} 这一段（真实实现走 LockUtils + Redis） */
    static class TestGuard extends EventInboxGuard {

        private final RuntimeException lockFailure;

        TestGuard(IEventInboxRepository repository, RuntimeException lockFailure) {
            this(repository, lockFailure, null);
        }

        TestGuard(IEventInboxRepository repository, RuntimeException lockFailure, SavepointTxManager txManager) {
            super(repository, new EventKeys("test"), new EventProperties(),
                    new TransactionTemplate(txManager != null ? txManager : new FakeTxManager()));
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

    /** 最小事务管理器：让 transactionTemplate 真进入事务（不连库）；事务对象不支持保存点 */
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

    /** 模拟 DataSourceTransactionManager：事务对象支持保存点并记账，用于断言"冲突回滚到保存点、事务照常提交" */
    static class SavepointTxManager extends AbstractPlatformTransactionManager {

        final RecordingSavepoints savepoints = new RecordingSavepoints();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return savepoints;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }
    }

    /** 保存点记账桩 */
    static class RecordingSavepoints implements SavepointManager {

        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger rolledBack = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();

        @Override
        public Object createSavepoint() {
            created.incrementAndGet();
            return new Object();
        }

        @Override
        public void rollbackToSavepoint(Object savepoint) {
            rolledBack.incrementAndGet();
        }

        @Override
        public void releaseSavepoint(Object savepoint) {
            released.incrementAndGet();
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
