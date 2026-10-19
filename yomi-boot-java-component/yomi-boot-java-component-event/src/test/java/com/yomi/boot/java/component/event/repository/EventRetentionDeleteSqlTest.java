package com.yomi.boot.java.component.event.repository;

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
 * 保留期删除的方言语句选择：受限删除两库语法不同，XML 里按 {@code databaseId} 各写一条，
 * MyBatis 按当前数据源选（方言由 ds 的 {@code DsDatabaseIdProvider} 解析为 mysql / pgsql）。
 * 这里用真实 MyBatis 解析器验证"选中的是哪一条"，两条语句各自保持简单、直接。
 */
class EventRetentionDeleteSqlTest {

    private static final String OUTBOX_XML = "mapper/EventOutboxMapper.xml";
    private static final String INBOX_XML = "mapper/EventInboxMapper.xml";
    private static final String OUTBOX_DELETE =
            "com.yomi.boot.java.component.event.repository.mapper.EventOutboxMapper.deleteSentSuccessBefore";
    private static final String INBOX_DELETE =
            "com.yomi.boot.java.component.event.repository.mapper.EventInboxMapper.deleteCreatedBefore";
    private static final String OUTBOX_OLDEST =
            "com.yomi.boot.java.component.event.repository.mapper.EventOutboxMapper.selectOldestSentSuccessTime";
    private static final String INBOX_OLDEST =
            "com.yomi.boot.java.component.event.repository.mapper.EventInboxMapper.selectOldestCreateTime";

    @Test
    void mysqlVariantIsNativeLimitedDeleteWithoutSubquery() throws Exception {
        Configuration configuration = parse("mysql");

        String outbox = norm(sql(configuration, OUTBOX_DELETE, Map.of("serviceName", "svc", "status", "SENT_SUCCESS",
                "sentTimeBefore", Instant.parse("2026-10-01T00:00:00Z"), "limit", 500)));
        String inbox = norm(sql(configuration, INBOX_DELETE, Map.of("serviceName", "svc",
                "createTimeBefore", Instant.parse("2026-10-01T00:00:00Z"), "limit", 500)));

        assertTrue(outbox.startsWith("DELETE FROM sys_event_outbox WHERE"), outbox);
        assertTrue(outbox.endsWith("LIMIT ?"), outbox);
        assertFalse(outbox.contains("IN (SELECT"), "MySQL 方言用原生 DELETE ... LIMIT，不应有子查询: " + outbox);
        assertTrue(inbox.startsWith("DELETE FROM sys_event_inbox WHERE"), inbox);
        assertTrue(inbox.endsWith("LIMIT ?"), inbox);
        assertFalse(inbox.contains("IN (SELECT"), inbox);
    }

    @Test
    void pgsqlVariantBoundsWithSingleLevelIdSubquery() throws Exception {
        Configuration configuration = parse("pgsql");

        String outbox = norm(sql(configuration, OUTBOX_DELETE, Map.of("serviceName", "svc", "status", "SENT_SUCCESS",
                "sentTimeBefore", Instant.parse("2026-10-01T00:00:00Z"), "limit", 500)));
        String inbox = norm(sql(configuration, INBOX_DELETE, Map.of("serviceName", "svc",
                "createTimeBefore", Instant.parse("2026-10-01T00:00:00Z"), "limit", 500)));

        assertTrue(outbox.contains("WHERE ctid IN (SELECT ctid FROM sys_event_outbox"), outbox);
        assertTrue(outbox.endsWith("LIMIT ?)"), outbox);
        assertEquals(1, count(outbox, "SELECT"), "只允许一层子查询（不再为双库通用包第二层派生表）: " + outbox);
        assertTrue(inbox.contains("WHERE ctid IN (SELECT ctid FROM sys_event_inbox"), inbox);
        assertEquals(1, count(inbox, "SELECT"), inbox);
    }

    /** 方言不在支持列表（非 MySQL/PG）：语句不注册 → 宿主在方言提供者处即启动失败 */
    @Test
    void unsupportedDialectRegistersNoStatement() throws Exception {
        Configuration configuration = parse("oracle");

        assertFalse(configuration.hasStatement(OUTBOX_DELETE));
        assertFalse(configuration.hasStatement(INBOX_DELETE));
    }

    /** 落后度量语句与方言无关：两库同一条，只取 MIN(时间列) */
    @Test
    void oldestTimeQueriesAreDialectNeutral() throws Exception {
        String outbox = norm(sql(parse("mysql"), OUTBOX_OLDEST,
                Map.of("serviceName", "svc", "status", "SENT_SUCCESS")));
        String inbox = norm(sql(parse("pgsql"), INBOX_OLDEST, Map.of("serviceName", "svc")));

        assertTrue(outbox.startsWith("SELECT MIN(sent_time) FROM sys_event_outbox WHERE"), outbox);
        assertTrue(outbox.contains("status = ?"), "只统计可删状态的行: " + outbox);
        assertTrue(inbox.startsWith("SELECT MIN(create_time) FROM sys_event_inbox WHERE"), inbox);
        assertEquals(2, count(outbox, "?"), "只按服务名与状态过滤: " + outbox);
        assertEquals(1, count(inbox, "?"), "只按服务名过滤: " + inbox);
    }

    // ===== 夹具 =====

    private static Configuration parse(String databaseId) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setDatabaseId(databaseId);
        for (String xml : new String[]{OUTBOX_XML, INBOX_XML}) {
            try (InputStream in = Resources.getResourceAsStream(xml)) {
                new XMLMapperBuilder(in, configuration, xml, configuration.getSqlFragments()).parse();
            }
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
