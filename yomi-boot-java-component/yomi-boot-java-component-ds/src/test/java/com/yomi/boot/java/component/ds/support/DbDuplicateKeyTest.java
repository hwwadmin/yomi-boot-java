package com.yomi.boot.java.component.ds.support;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 唯一键冲突识别（组件共用单一实现）：
 * <ul>
 *     <li>Spring 统一抽象与各驱动原始错误码都要命中；</li>
 *     <li><b>外键等其它完整性约束不得命中</b>——SQLState 23000 同时覆盖它们，误判会把真实约束错误静默吞掉。</li>
 * </ul>
 */
class DbDuplicateKeyTest {

    @Test
    void springAbstractionMatches() {
        assertTrue(DbDuplicateKey.isDuplicateKey(new DuplicateKeyException("uk_service_event_id")));
    }

    @Test
    void mysqlDuplicateEntryMatches() {
        assertTrue(DbDuplicateKey.isDuplicateKey(new SQLException("Duplicate entry", "23000", 1062)));
    }

    @Test
    void postgresUniqueViolationMatches() {
        assertTrue(DbDuplicateKey.isDuplicateKey(new SQLException("unique_violation", "23505", 0)));
    }

    @Test
    void sqlServerUniqueCodesMatch() {
        assertTrue(DbDuplicateKey.isDuplicateKey(new SQLException("dup index", "23000", 2601)));
        assertTrue(DbDuplicateKey.isDuplicateKey(new SQLException("dup constraint", "23000", 2627)));
    }

    @Test
    void oracleUniqueConstraintMatches() {
        assertTrue(DbDuplicateKey.isDuplicateKey(new SQLException("ORA-00001", "23000", 1)));
    }

    /** 同样的 SQLState 23000，但错误码是外键约束（MySQL 1452）：绝不能当成重复键 */
    @Test
    void foreignKeyViolationIsNotDuplicateKey() {
        assertFalse(DbDuplicateKey.isDuplicateKey(new SQLException("Cannot add or update a child row", "23000", 1452)),
                "SQLState 23000 覆盖外键/非空等约束，不得按 23000 判定重复键");
    }

    @Test
    void nestedCauseIsSearched() {
        assertTrue(DbDuplicateKey.isDuplicateKey(
                new RuntimeException("业务包装", new SQLException("Duplicate entry", "23000", 1062))));
    }

    @Test
    void unrelatedAndNullAreRejected() {
        assertFalse(DbDuplicateKey.isDuplicateKey(new SQLException("timeout", "HYT00", 0)));
        assertFalse(DbDuplicateKey.isDuplicateKey(new IllegalStateException("业务异常")));
        assertFalse(DbDuplicateKey.isDuplicateKey(null));
    }

}
