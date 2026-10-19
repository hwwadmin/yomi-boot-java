package com.yomi.boot.java.component.httplog.repository;

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
 * 保留期限额删除的方言语句选择：两库限额删语法不同，XML 里按 {@code databaseId} 各写一条，
 * MyBatis 按当前数据源选（方言由 ds 的 {@code DsDatabaseIdProvider} 解析为 mysql / pgsql）。
 * 这里用真实 MyBatis 解析器验证"选中的是哪一条"；最旧时间查询不带方言，两库同一条。
 *
 * @author zwei
 */
class HttpLogRetentionDeleteSqlTest {

    private static final String XML = "mapper/HttpLogMapper.xml";
    private static final String DELETE =
            "com.yomi.boot.java.component.httplog.repository.mapper.HttpLogMapper.deleteExpiredLimited";
    private static final String OLDEST =
            "com.yomi.boot.java.component.httplog.repository.mapper.HttpLogMapper.selectOldestCreateTime";

    @Test
    void mysqlVariantIsNativeLimitedDeleteWithoutSubquery() throws Exception {
        String sql = norm(sql(parse("mysql"), DELETE, Map.of("serviceName", "svc",
                "createTimeBefore", Instant.parse("2026-10-01T00:00:00Z"), "limit", 1000)));

        assertTrue(sql.startsWith("DELETE FROM sys_http_log WHERE"), sql);
        assertTrue(sql.endsWith("LIMIT ?"), sql);
        assertFalse(sql.contains("IN (SELECT"), "MySQL 方言用原生 DELETE ... LIMIT，不应有子查询: " + sql);
        assertFalse(sql.contains("ORDER BY"), "谓词已界定全部过期行，排序只会让优化器退化成 filesort: " + sql);
    }

    @Test
    void pgsqlVariantBoundsWithSingleLevelCtidSubquery() throws Exception {
        String sql = norm(sql(parse("pgsql"), DELETE, Map.of("serviceName", "svc",
                "createTimeBefore", Instant.parse("2026-10-01T00:00:00Z"), "limit", 1000)));

        assertTrue(sql.contains("WHERE ctid IN (SELECT ctid FROM sys_http_log"), sql);
        assertTrue(sql.endsWith("LIMIT ?)"), sql);
        assertEquals(1, count(sql, "SELECT"), "只允许一层子查询: " + sql);
    }

    /** 最旧时间查询不带方言：两库同一条（结果按 Instant 映射） */
    @Test
    void oldestCreateTimeIsDialectIndependent() throws Exception {
        for (String databaseId : new String[]{"mysql", "pgsql"}) {
            String sql = norm(sql(parse(databaseId), OLDEST, Map.of("serviceName", "svc")));
            assertEquals("SELECT MIN(create_time) FROM sys_http_log WHERE service_name = ?", sql,
                    "方言 " + databaseId);
        }
    }

    /** 方言不在支持列表（非 MySQL/PG）：方言语句不注册 → 宿主在方言提供者处即启动失败 */
    @Test
    void unsupportedDialectRegistersNoLimitedDelete() throws Exception {
        Configuration configuration = parse("oracle");

        assertFalse(configuration.hasStatement(DELETE));
        assertTrue(configuration.hasStatement(OLDEST), "不带方言的语句对所有方言注册");
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

    /** SQL 比较前把连续空白压成单空格，断言不绑定 XML 缩进 */
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
