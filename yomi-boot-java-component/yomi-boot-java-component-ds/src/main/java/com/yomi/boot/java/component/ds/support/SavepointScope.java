package com.yomi.boot.java.component.ds.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.TransactionStatus;

/**
 * 事务保存点作用域：包住"可能因唯一键冲突失败"的写库动作，失败后回滚到保存点，
 * 让 PostgreSQL 事务从 aborted 状态（后续语句报 25P02 "current transaction is aborted"）恢复可用，
 * 再继续读回已完成结果或抛出异常。</p>
 * <p>
 * PG 里事务内任一语句报错都会把整个事务打成 aborted，即便异常被捕获，后续语句（含外层事务的提交）都不再可用；
 * MySQL 无此限制，本类两边通用（每个作用域多 2 条 SAVEPOINT / RELEASE SAVEPOINT 语句）。
 * 事务管理器不支持保存点（如部分云托管事务管理器）时 {@link #open} 返回空作用域，各方法退化为 no-op。
 * </p>
 * <pre>{@code
 * SavepointScope scope = SavepointScope.open(status);
 * try {
 *     repository.save(row);
 * } catch (Exception e) {
 *     scope.rollbackTo();
 *     ...
 * } finally {
 *     scope.release();
 * }
 * }</pre>
 *
 * @author zwei
 */
@Slf4j
public final class SavepointScope {

    /** 事务状态；空作用域（不支持保存点）时也用不到 */
    private final TransactionStatus status;

    /** 事务保存点；null 表示未建立（事务管理器不支持保存点） */
    private final Object savepoint;

    private SavepointScope(TransactionStatus status, Object savepoint) {
        this.status = status;
        this.savepoint = savepoint;
    }

    /** 建立保存点；事务管理器不支持保存点时返回空作用域（各方法 no-op，行为回到"不回滚保存点"） */
    public static SavepointScope open(TransactionStatus status) {
        try {
            return new SavepointScope(status, status.createSavepoint());
        } catch (NestedTransactionNotSupportedException e) {
            log.debug("当前事务管理器不支持保存点：唯一键冲突后的读回在 PostgreSQL 上可能失败", e);
            return new SavepointScope(status, null);
        }
    }

    /** 回滚到保存点（PG 下让事务恢复可用）；失败仅记日志，交由外层事务整体回滚，不掩盖原始异常 */
    public void rollbackTo() {
        if (savepoint == null) {
            return;
        }
        try {
            status.rollbackToSavepoint(savepoint);
        } catch (RuntimeException e) {
            log.warn("回滚到保存点失败，将由外层事务整体回滚", e);
        }
    }

    /** 释放保存点（避免长事务内保存点堆积）；失败仅记日志，保存点随事务结束释放 */
    public void release() {
        if (savepoint == null) {
            return;
        }
        try {
            status.releaseSavepoint(savepoint);
        } catch (RuntimeException e) {
            log.warn("释放保存点失败（保存点随事务结束释放）", e);
        }
    }

}
