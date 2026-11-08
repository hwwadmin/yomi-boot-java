package com.yomi.boot.java.component.idempotency.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清理删除的方言语句选择：受限删除两库语法不同，XML 里按 {@code databaseId} 各写一条，
 * MyBatis 按当前数据源选（方言由 ds 的 {@code DsDatabaseIdProvider} 解析为 mysql / pgsql）。
 */
class IdempotencyRetentionDeleteSqlTest {

    private static final String XML = "mapper/IdempotencyRecordMapper.xml";
    private static final String STALE =
            "com.yomi.boot.java.component.idempotency.repository.mapper.IdempotencyRecordMapper.deleteStaleProcessing";
    private static final String EXPIRED =
            "com.yomi.boot.java.component.idempotency.repository.mapper.IdempotencyRecordMapper.deleteExpired";

    @Test
    void mysqlVariantIsNativeLimitedDeleteWithoutSubquery() throws Exception {
        Configuration configuration = parse("mysql");
        Map<String, Object> params = Map.of("processingStatus", 1,
                "before", Instant.parse("2026-10-01T00:00:00Z"), "limit", 200);

        String stale = norm(sql(configuration, STALE, params));
        String expired = norm(sql(configuration, EXPIRED, params));

        assertTrue(stale.startsWith("DELETE FROM t_sys_idempotency_record WHERE"), stale);
        assertTrue(stale.endsWith("LIMIT ?"), stale);
        assertFalse(stale.contains("IN (SELECT"), stale);
        assertTrue(expired.endsWith("LIMIT ?"), expired);
        assertFalse(expired.contains("IN (SELECT"), expired);
    }

    @Test
    void pgsqlVariantBoundsWithSingleLevelIdSubquery() throws Exception {
        Configuration configuration = parse("pgsql");
        Map<String, Object> params = Map.of("processingStatus", 1,
                "before", Instant.parse("2026-10-01T00:00:00Z"), "limit", 200);

        String stale = norm(sql(configuration, STALE, params));
        String expired = norm(sql(configuration, EXPIRED, params));

        assertTrue(stale.contains("WHERE ctid IN (SELECT ctid FROM t_sys_idempotency_record"), stale);
        assertTrue(stale.endsWith("LIMIT ?)"), stale);
        assertEquals(1, count(stale, "SELECT"), "只允许一层子查询: " + stale);
        assertTrue(expired.contains("WHERE ctid IN (SELECT ctid FROM t_sys_idempotency_record"), expired);
        assertEquals(1, count(expired, "SELECT"), expired);
    }

    @Test
    void unsupportedDialectRegistersNoStatement() throws Exception {
        Configuration configuration = parse("oracle");

        assertFalse(configuration.hasStatement(STALE));
        assertFalse(configuration.hasStatement(EXPIRED));
    }

    // ===== 夹具 =====

    private static Configuration parse(String databaseId) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setDatabaseId(databaseId);
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String sql(Configuration configuration, String statementId, Map<String, Object> params) {
        assertTrue(configuration.hasStatement(statementId), "缺少语句 " + statementId);
        return configuration.getMappedStatement(statementId).getBoundSql(new HashMap<>(params)).getSql();
    }

    /** SQL 比较前把连续空白压成单空格，断言不依赖 XML 缩进 */
    private static String norm(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static int count(String text, String token) {
        int n = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) {
            n++;
        }
        return n;
    }

}
