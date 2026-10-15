package com.yomi.boot.java.component.event.relay;

import com.yomi.boot.java.component.event.config.EventProperties;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.repository.EventOutboxDlqRepository;
import com.yomi.boot.java.component.event.repository.EventOutboxRepository;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxDlqMapper;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DLQ 重试异常路径：应记录日志并推进重试计数与退避。
 * <p>全部在 Kafka 发送之前失败，因此不需要 Kafka/Spring 上下文</p>
 */
class OutboxDispatcherDlqTest {

    private final EventProperties properties = new EventProperties();

    @Test
    void claimNotAcquiredLeavesRowUntouched() {
        Stubs stubs = new Stubs().claimResult(0);
        OutboxDispatcher dispatcher = dispatcher(stubs);

        assertDoesNotThrow(() -> dispatcher.dispatchFromDlq(outbox(9L), dlq(7L, 1)));

        assertTrue(stubs.dlqReads.isEmpty(), "未抢到 in-flight 不应继续查行");
        assertTrue(stubs.dlqUpdates.isEmpty() && stubs.dlqDeletes.isEmpty(), "未抢到 in-flight 不应改任何行");
    }

    @Test
    void missingDlqRowSkipsQuietly() {
        Stubs stubs = new Stubs().claimResult(1).dlqRow(null);
        OutboxDispatcher dispatcher = dispatcher(stubs);

        assertDoesNotThrow(() -> dispatcher.dispatchFromDlq(outbox(9L), dlq(7L, 1)));

        assertTrue(stubs.dlqUpdates.isEmpty(), "DLQ 行都没了不应再回写");
        assertTrue(stubs.dlqDeletes.isEmpty());
    }

    @Test
    void missingOutboxRowClearsDlqPointer() {
        // 发件箱行不存在时清理 DLQ 行（指针失效）
        Stubs stubs = new Stubs().claimResult(1).dlqRow(dlq(7L, 1)).outboxRow(null);
        OutboxDispatcher dispatcher = dispatcher(stubs);

        assertDoesNotThrow(() -> dispatcher.dispatchFromDlq(outbox(9L), dlq(7L, 1)));

        assertEquals(List.of(7L), stubs.dlqDeletes, "指针失效应清理 DLQ 行（与扫描期 processOneDlq 语义一致）");
    }

    @Test
    void alreadySentOutboxClearsDlqRow() {
        EventOutbox sent = outbox(9L);
        sent.setStatus(OutboxStatus.SENT_SUCCESS);
        Stubs stubs = new Stubs().claimResult(1).dlqRow(dlq(7L, 1)).outboxRow(sent);
        OutboxDispatcher dispatcher = dispatcher(stubs);

        assertDoesNotThrow(() -> dispatcher.dispatchFromDlq(outbox(9L), dlq(7L, 1)));

        assertEquals(List.of(7L), stubs.dlqDeletes, "已发送成功的行不应再重发，DLQ 行应清理");
    }

    @Test
    void preparationFailureAdvancesRetryCountInsteadOfSilentDeath() {
        // 查行阶段异常：记录 ERROR 并推进重试
        Stubs stubs = new Stubs().claimResult(1).dlqReadFailure(new SQLException("dlq table broken"));
        OutboxDispatcher dispatcher = dispatcher(stubs);

        assertDoesNotThrow(() -> dispatcher.dispatchFromDlq(outbox(9L), dlq(7L, null)));

        assertEquals(1, stubs.dlqUpdates.size(), "准备失败应回写重试计数");
        EventOutboxDlq updated = stubs.dlqUpdates.get(0);
        assertEquals(1, updated.getRetryCount(), "重试次数应递增");
        assertNotNull(updated.getRetryLastTime());
        assertNotNull(updated.getRetryAfterTime(), "应写入退避时间");
        assertNull(updated.getErrorMsg(), "未到上限不应打 EXHAUSTED 标记");
    }

    @Test
    void retryWritebackFailureIsSwallowedWithLog() {
        // 回写重试计数失败时也应记录日志
        Stubs stubs = new Stubs().claimResult(1).dlqReadFailure(new SQLException("boom"))
                .updateFailure(new SQLException("update broken"));
        OutboxDispatcher dispatcher = dispatcher(stubs);

        assertDoesNotThrow(() -> dispatcher.dispatchFromDlq(outbox(9L), dlq(7L, null)));
    }

    /** 投递er */
    private OutboxDispatcher dispatcher(Stubs stubs) {
        EventOutboxRepository outboxRepository = new EventOutboxRepository(stubs.outboxMapper());
        EventOutboxDlqRepository dlqRepository = new EventOutboxDlqRepository(stubs.dlqMapper());
        return new OutboxDispatcher(properties, outboxRepository, dlqRepository,
                new TransactionTemplate(proxy(PlatformTransactionManager.class)));
    }

    /** 构造发件箱 Mapper 桩 */
    private static EventOutbox outbox(Long id) {
        EventOutbox row = new EventOutbox();
        row.setId(id);
        row.setEventId("evt-" + id);
        row.setTopic("topic-a");
        row.setPayload("{}");
        row.setStatus(OutboxStatus.SENT_FAIL);
        return row;
    }

    /** 构造 DLQ Mapper 桩 */
    private static EventOutboxDlq dlq(Long id, Integer retryCount) {
        EventOutboxDlq row = new EventOutboxDlq();
        row.setId(id);
        row.setEventOutboxId(9L);
        row.setRetryCount(retryCount);
        return row;
    }

    /** 从 ApplicationContext 取 Bean 的 ObjectProvider */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(OutboxDispatcherDlqTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "stub";
                    }
                    return null;
                });
    }

    /** 以代理 Mapper 记录/驱动各仓储调用 */
    private static final class Stubs {

        private int claimResult = 1;
        private EventOutboxDlq dlqRow;
        private EventOutbox outboxRow;
        private Exception dlqReadFailure;
        private Exception updateFailure;

        private final List<Long> dlqReads = new ArrayList<>();
        private final List<EventOutboxDlq> dlqUpdates = new ArrayList<>();
        private final List<Long> dlqDeletes = new ArrayList<>();

        Stubs claimResult(int value) {
            this.claimResult = value;
            return this;
        }

        Stubs dlqRow(EventOutboxDlq row) {
            this.dlqRow = row;
            return this;
        }

        Stubs outboxRow(EventOutbox row) {
            this.outboxRow = row;
            return this;
        }

        Stubs dlqReadFailure(Exception failure) {
            this.dlqReadFailure = failure;
            return this;
        }

        Stubs updateFailure(Exception failure) {
            this.updateFailure = failure;
            return this;
        }

        EventOutboxDlqMapper dlqMapper() {
            return (EventOutboxDlqMapper) Proxy.newProxyInstance(OutboxDispatcherDlqTest.class.getClassLoader(),
                    new Class<?>[]{EventOutboxDlqMapper.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "claimInFlight" -> claimResult;
                        case "selectById" -> {
                            dlqReads.add((Long) args[0]);
                            if (dlqReadFailure != null) {
                                throw dlqReadFailure;
                            }
                            yield dlqRow;
                        }
                        case "updateById" -> {
                            if (updateFailure != null) {
                                throw updateFailure;
                            }
                            dlqUpdates.add((EventOutboxDlq) args[0]);
                            yield 1;
                        }
                        case "deleteById" -> {
                            dlqDeletes.add((Long) args[0]);
                            yield 1;
                        }
                        default -> defaultReturn(proxy, method, args);
                    });
        }

        EventOutboxMapper outboxMapper() {
            return (EventOutboxMapper) Proxy.newProxyInstance(OutboxDispatcherDlqTest.class.getClassLoader(),
                    new Class<?>[]{EventOutboxMapper.class}, (proxy, method, args) -> {
                        if ("selectById".equals(method.getName())) {
                            return outboxRow;
                        }
                        return defaultReturn(proxy, method, args);
                    });
        }

        /** Mock 默认返回值 */
        private static Object defaultReturn(Object proxy, java.lang.reflect.Method method, Object[] args) {
            if ("toString".equals(method.getName())) {
                return "stub";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            }
            return null;
        }
    }

}
