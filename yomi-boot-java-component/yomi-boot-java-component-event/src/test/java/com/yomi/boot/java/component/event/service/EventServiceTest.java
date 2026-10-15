package com.yomi.boot.java.component.event.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageResponse;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件查询/运维服务测试：状态与详情、requeue 状态机（SENT_SUCCESS 拒绝 / 失败重置并清 DLQ）、
 * 消费凭证、DLQ 分页 eventId 补全、能力未启用时的明确异常（假仓储动态代理）
 *
 * @author zwei
 */
class EventServiceTest {

    @Test
    void outboxStatus_foundAndMissing() {
        FakeOutbox outbox = new FakeOutbox();
        outbox.row = row(OutboxStatus.SENT_FAIL);
        EventService service = service(outbox.repo(), null, null);

        assertEquals(OutboxStatus.SENT_FAIL, service.outboxStatus("evt-1"));
        assertEquals("test", outbox.lastService);

        outbox.row = null;
        assertNull(service.outboxStatus("evt-none"));
    }

    @Test
    void requeue_sentSuccess_rejectedWithoutSideEffects() {
        FakeOutbox outbox = new FakeOutbox();
        outbox.row = row(OutboxStatus.SENT_SUCCESS);
        FakeDlq dlq = new FakeDlq();
        EventService service = service(outbox.repo(), dlq.repo(), null);

        assertFalse(service.requeue("evt-1"));
        assertFalse(outbox.calls.contains("resetToPending"));
        assertFalse(dlq.calls.contains("deleteByEventOutboxId"));
    }

    @Test
    void requeue_failed_resetsAndDeletesDlq() {
        List<String> calls = new ArrayList<>();
        FakeOutbox outbox = new FakeOutbox(calls);
        outbox.row = row(OutboxStatus.SENT_FAIL);
        FakeDlq dlq = new FakeDlq(calls);
        EventService service = service(outbox.repo(), dlq.repo(), null);

        assertTrue(service.requeue("evt-1"));
        // 顺序：先删 DLQ（阻断自动重试路径），后重置 PENDING（中继重新抓取）
        assertTrue(calls.indexOf("deleteByEventOutboxId") >= 0);
        assertTrue(calls.indexOf("deleteByEventOutboxId") < calls.indexOf("resetToPending"));
        assertEquals("evt-1", outbox.lastEventId);
    }

    @Test
    void requeue_runsBothWritesInsideOneTransaction() {
        List<String> txNotes = new ArrayList<>();
        FakeOutbox outbox = new FakeOutbox(new ArrayList<>(), txNotes);
        outbox.row = row(OutboxStatus.SENT_FAIL);
        FakeDlq dlq = new FakeDlq(new ArrayList<>(), txNotes);
        EventService service = service(outbox.repo(), dlq.repo(), null);

        assertTrue(service.requeue("evt-1"));

        assertTrue(txNotes.contains("dlq:inTx"), "删 DLQ 必须在事务内: " + txNotes);
        assertTrue(txNotes.contains("outbox:inTx"), "重置 PENDING 必须在事务内: " + txNotes);
    }

    @Test
    void requeue_rollsBackDlqDeletionWhenResetFails() {
        List<String> txNotes = new ArrayList<>();
        FakeOutbox outbox = new FakeOutbox(new ArrayList<>(), txNotes);
        outbox.row = row(OutboxStatus.SENT_FAIL);
        outbox.failReset = true;
        FakeDlq dlq = new FakeDlq(new ArrayList<>(), txNotes);
        EventService service = service(outbox.repo(), dlq.repo(), null);

        assertThrows(IllegalStateException.class, () -> service.requeue("evt-1"));

        assertTrue(txNotes.contains("dlq:deleteUndone"),
                "第二步失败必须连同 DLQ 删除一起回滚，否则留下永不投递的事件: " + txNotes);
    }

    @Test
    void findOutbox_mapsPayload() {
        FakeOutbox outbox = new FakeOutbox();
        EventOutbox entity = row(OutboxStatus.SENT_FAIL);
        entity.setPayload("{\"a\":1}");
        outbox.row = entity;
        EventService service = service(outbox.repo(), null, null);

        assertEquals("{\"a\":1}", service.findOutbox("evt-1").getPayload());
    }

    @Test
    void requeue_missing_returnsFalse() {
        FakeOutbox outbox = new FakeOutbox();
        EventService service = service(outbox.repo(), null, null);

        assertFalse(service.requeue("evt-none"));
    }

    @Test
    void consumed_checksInboxOfCurrentService() {
        FakeInbox inbox = new FakeInbox();
        inbox.exists = true;
        EventService service = service(null, null, inbox.repo());

        assertTrue(service.consumed("evt-1"));
        assertEquals("test", inbox.lastService);
    }

    @Test
    void consumed_withoutConsumerEnabled_failsFastWithHint() {
        EventService service = service(null, null, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.consumed("evt-1"));
        assertTrue(ex.getMessage().contains("consumer-enabled"), ex.getMessage());
    }

    @Test
    void pageOutbox_mapsSummaryAndPassesCurrentService() {
        FakeOutbox outbox = new FakeOutbox();
        outbox.pageRows = List.of(row(OutboxStatus.PENDING));
        outbox.pageTotal = 1;
        EventService service = service(outbox.repo(), null, null);

        PageResponse<EventOutboxModel> response = service.pageOutbox(new EventOutboxQuery());

        assertEquals(1, response.getData().size());
        assertEquals("evt-1", response.getData().get(0).getEventId());
        assertNull(response.getData().get(0).getPayload());
        assertEquals("test", outbox.lastService);
    }

    @Test
    void pageDlq_fillsEventIdsInOneBatch() {
        FakeOutbox outbox = new FakeOutbox();
        outbox.byIds = List.of(row(OutboxStatus.SENT_FAIL));
        FakeDlq dlq = new FakeDlq();
        dlq.pageRows = List.of(dlqRow(7L));
        EventService service = service(outbox.repo(), dlq.repo(), null);

        PageResponse<EventDlqModel> response = service.pageDlq(new EventDlqQuery());

        assertEquals(1, response.getData().size());
        assertEquals("evt-1", response.getData().get(0).getEventId());
        assertTrue(outbox.calls.contains("findByIds"));
    }

    @Test
    void resetDlqRetryCount_forwardsRange() {
        FakeDlq dlq = new FakeDlq();
        dlq.resetRows = 3;
        EventService service = service(null, dlq.repo(), null);

        Instant from = Instant.now().minusSeconds(60);
        assertEquals(3, service.resetDlqRetryCount(from, Instant.now()));
        assertEquals("test", dlq.lastService);
    }

    // ===== 夹具 =====

    private static EventService service(IEventOutboxRepository outbox, IEventOutboxDlqRepository dlq,
                                        IEventInboxRepository inbox) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (outbox != null) {
            factory.addBean("outbox", outbox);
        }
        if (dlq != null) {
            factory.addBean("dlq", dlq);
        }
        if (inbox != null) {
            factory.addBean("inbox", inbox);
        }
        return new EventService(
                factory.getBeanProvider(IEventOutboxRepository.class),
                factory.getBeanProvider(IEventOutboxDlqRepository.class),
                factory.getBeanProvider(IEventInboxRepository.class),
                new EventKeys("test"),
                new TransactionTemplate(new FakeTxManager()));
    }

    /** 最小事务管理器：进入真实事务、能在回滚时触发 afterCompletion（不连库） */
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

    private static EventOutbox row(OutboxStatus status) {
        EventOutbox entity = new EventOutbox();
        entity.setId(7L);
        entity.setEventId("evt-1");
        entity.setTopic("topic-a");
        entity.setStatus(status);
        return entity;
    }

    private static EventOutboxDlq dlqRow(Long outboxId) {
        EventOutboxDlq entity = new EventOutboxDlq();
        entity.setId(11L);
        entity.setEventOutboxId(outboxId);
        entity.setErrorMsg("boom");
        entity.setRetryCount(2);
        return entity;
    }

    /** 假发件箱仓储：记录调用与关键参数 */
    static class FakeOutbox {
        final List<String> calls;

        final List<String> txNotes;

        FakeOutbox() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        FakeOutbox(List<String> calls) {
            this(calls, new ArrayList<>());
        }

        FakeOutbox(List<String> calls, List<String> txNotes) {
            this.calls = calls;
            this.txNotes = txNotes;
        }
        EventOutbox row;
        List<EventOutbox> byIds = List.of();
        List<EventOutbox> pageRows = List.of();
        long pageTotal;
        String lastService;
        String lastEventId;

        /** true 时 resetToPending 抛错（模拟第二步失败，用于验证回滚） */
        boolean failReset;

        IEventOutboxRepository repo() {
            return (IEventOutboxRepository) Proxy.newProxyInstance(
                    IEventOutboxRepository.class.getClassLoader(), new Class<?>[]{IEventOutboxRepository.class},
                    (proxy, method, args) -> {
                        calls.add(method.getName());
                        return switch (method.getName()) {
                            case "findByEventId" -> {
                                lastService = (String) args[0];
                                lastEventId = (String) args[1];
                                yield row;
                            }
                            case "resetToPending" -> {
                                txNotes.add(TransactionSynchronizationManager.isActualTransactionActive()
                                        ? "outbox:inTx" : "outbox:noTx");
                                if (failReset) {
                                    throw new IllegalStateException("resetToPending 失败（模拟 DB 故障）");
                                }
                                yield 1;
                            }
                            case "findByIds" -> byIds;
                            case "pageOutbox" -> {
                                lastService = (String) args[1];
                                Page<EventOutbox> page = new Page<>(1, 10);
                                page.setRecords(pageRows);
                                page.setTotal(pageTotal);
                                yield page;
                            }
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }
    }

    /** 假死信仓储 */
    static class FakeDlq {
        final List<String> calls;

        final List<String> txNotes;

        FakeDlq() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        FakeDlq(List<String> calls) {
            this(calls, new ArrayList<>());
        }

        FakeDlq(List<String> calls, List<String> txNotes) {
            this.calls = calls;
            this.txNotes = txNotes;
        }
        List<EventOutboxDlq> pageRows = List.of();
        long resetRows;
        String lastService;

        IEventOutboxDlqRepository repo() {
            return (IEventOutboxDlqRepository) Proxy.newProxyInstance(
                    IEventOutboxDlqRepository.class.getClassLoader(),
                    new Class<?>[]{IEventOutboxDlqRepository.class}, (proxy, method, args) -> {
                        calls.add(method.getName());
                        return switch (method.getName()) {
                            case "deleteByEventOutboxId" -> {
                                txNotes.add(TransactionSynchronizationManager.isActualTransactionActive()
                                        ? "dlq:inTx" : "dlq:noTx");
                                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                                    // 证明该写入确实挂在事务上：回滚时回调会被触发
                                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                        @Override
                                        public void afterCompletion(int status) {
                                            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                                                txNotes.add("dlq:deleteUndone");
                                            }
                                        }
                                    });
                                }
                                yield 1;
                            }
                            case "resetRetryCountInRange" -> {
                                lastService = (String) args[0];
                                yield resetRows;
                            }
                            case "pageDlq" -> {
                                lastService = (String) args[1];
                                Page<EventOutboxDlq> page = new Page<>(1, 10);
                                page.setRecords(pageRows);
                                page.setTotal(pageRows.size());
                                yield page;
                            }
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }
    }

    /** 假收件箱仓储 */
    static class FakeInbox {
        boolean exists;
        String lastService;

        IEventInboxRepository repo() {
            return (IEventInboxRepository) Proxy.newProxyInstance(
                    IEventInboxRepository.class.getClassLoader(), new Class<?>[]{IEventInboxRepository.class},
                    (proxy, method, args) -> {
                        if ("exists".equals(method.getName())) {
                            lastService = (String) args[0];
                            return exists;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        if (type == boolean.class || type == Boolean.class) {
            return false;
        }
        return null;
    }

}
