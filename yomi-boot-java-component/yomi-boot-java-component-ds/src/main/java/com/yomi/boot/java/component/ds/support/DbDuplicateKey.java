package com.yomi.boot.java.component.ds.support;

import com.yomi.boot.java.base.utils.ExUtils;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

/**
 * 唯一键冲突识别（组件共用单一实现：event 的"重复消息"、idempotency 的"同键占位冲突"都判定在这里）。
 * <p>
 * 识别顺序：Spring 的 {@link DuplicateKeyException}（框架已把各驱动翻译成统一抽象）→ 驱动原始错误码，
 * MyBatis 场景下常见的是原始 {@link SQLException} 挂在 cause 链上。
 * SQLState 23000 不参与判定：它同时覆盖外键、非空等完整性约束，按它判定会把真实的约束错误
 * 当成"重复投递"跳过，例如 outbox 的 FK 失败
 *
 * @author zwei
 */
public final class DbDuplicateKey {

    /** MySQL：1062 Duplicate entry */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;
    /** PostgreSQL（及 H2）：SQLState 23505 unique_violation */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
    /** SQL Server：2601 唯一索引重复 */
    private static final int SQLSERVER_DUPLICATE_INDEX = 2601;
    /** SQL Server：2627 唯一约束冲突 */
    private static final int SQLSERVER_UNIQUE_CONSTRAINT = 2627;
    /** Oracle：ORA-00001 唯一约束冲突 */
    private static final int ORACLE_UNIQUE_CONSTRAINT = 1;

    private DbDuplicateKey() {
    }

    /** 异常链上是否出现唯一键冲突（各驱动统一判定） */
    public static boolean isDuplicateKey(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (ExUtils.isCausedBy(throwable, DuplicateKeyException.class)) {
            return true;
        }
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof SQLException sql && isUniqueViolationCode(sql)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /** 按驱动错误码或 SQLState 23505 判断是否为唯一约束冲突 */
    private static boolean isUniqueViolationCode(SQLException sql) {
        return sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY
                || sql.getErrorCode() == SQLSERVER_DUPLICATE_INDEX
                || sql.getErrorCode() == SQLSERVER_UNIQUE_CONSTRAINT
                || sql.getErrorCode() == ORACLE_UNIQUE_CONSTRAINT
                || SQLSTATE_UNIQUE_VIOLATION.equals(sql.getSQLState());
    }

}
