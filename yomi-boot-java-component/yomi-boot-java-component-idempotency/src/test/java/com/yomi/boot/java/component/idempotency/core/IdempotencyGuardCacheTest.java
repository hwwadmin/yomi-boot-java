package com.yomi.boot.java.component.idempotency.core;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 结果缓存的写入时机：守卫可能运行在调用方已有的事务中，
 * 此时"事务模板 execute 返回"并不代表事务已提交，缓存必须推迟到提交之后写。
 */
class IdempotencyGuardCacheTest {

    /** 与 DataSourceTransactionManager 同构的最小事务管理器：只有提交才让事务内的写入可见 */
    static class RecordingTxManager extends AbstractPlatformTransactionManager {
        final List<String> events = new ArrayList<>();
        private final ThreadLocal<Boolean> bound = new ThreadLocal<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return bound.get() != null;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            bound.set(Boolean.TRUE);
            events.add("BEGIN");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("COMMIT");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("ROLLBACK");
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            bound.remove();
        }
    }

    @Test
    void runsImmediatelyWithoutActiveTransaction() {
        AtomicInteger runs = new AtomicInteger();
        IdempotencyGuard.runAfterCommit(runs::incrementAndGet);
        assertEquals(1, runs.get());
    }

    /** 内层 execute 返回时外层事务尚未提交，缓存必须等到外层提交才写 */
    @Test
    void writesAfterOuterCommitNotWhenInnerExecuteReturns() {
        TransactionTemplate tx = new TransactionTemplate(new RecordingTxManager());
        AtomicBoolean writtenBeforeCommit = new AtomicBoolean();
        AtomicInteger runs = new AtomicInteger();

        tx.execute(status -> {
            // 与守卫的 transactionTemplate.execute 同形态：默认传播 REQUIRED，加入外层事务且不自行提交
            tx.execute(inner -> null);
            IdempotencyGuard.runAfterCommit(runs::incrementAndGet);
            writtenBeforeCommit.set(runs.get() > 0);
            return null;
        });

        assertFalse(writtenBeforeCommit.get(), "外层事务提交前不得写缓存");
        assertEquals(1, runs.get(), "外层事务提交后必须写缓存");
    }

    /** 外层事务回滚时缓存不写：重试若命中缓存会返回一个未提交的成功结果 */
    @Test
    void neverWritesWhenOuterTransactionRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(new RecordingTxManager());
        AtomicInteger runs = new AtomicInteger();

        try {
            tx.execute(status -> {
                tx.execute(inner -> null);
                IdempotencyGuard.runAfterCommit(runs::incrementAndGet);
                throw new IllegalStateException("门面后续步骤失败，外层事务回滚");
            });
        } catch (IllegalStateException expected) {
            // 外层事务已回滚
        }

        assertEquals(0, runs.get());
    }
}
