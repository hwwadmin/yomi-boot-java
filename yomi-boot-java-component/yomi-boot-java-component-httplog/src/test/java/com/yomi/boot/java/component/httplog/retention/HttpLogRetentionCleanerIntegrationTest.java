package com.yomi.boot.java.component.httplog.retention;

import com.yomi.boot.java.component.desensitize.config.DesensitizeAutoConfiguration;
import com.yomi.boot.java.component.ds.config.DsAutoConfiguration;
import com.yomi.boot.java.component.httplog.config.HttpLogAutoConfiguration;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.repository.IHttpLogRepository;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 保留期清理真机验证（默认跳过）：MySQL 与 PG 各跑一遍，验证方言语句在真库落地
 * （MySQL 原生 {@code DELETE ... LIMIT} / PG {@code ctid} 子查询）、limit 原样生效、
 * 单轮上限精确封顶、删空即止、保留期内行不受影响、落后度量取自最旧一行。
 * <p>执行方式：配 {@code yomi.test.mysql.url} / {@code yomi.test.pg.url}（或环境变量
 * {@code YOMI_TEST_MYSQL_URL} / {@code YOMI_TEST_PG_URL}），账号口令经 {@code yomi.test.<库>.username|password}
 * 传入；未配置时整体跳过。用例只增删自己服务名（{@value #SERVICE}）的行。
 *
 * @author zwei
 */
class HttpLogRetentionCleanerIntegrationTest {

    /** 用例专用服务名（只删自己插入的行） */
    private static final String SERVICE = "httplog-retention-it";

    /** 过期行数：10 天前写入 */
    private static final int EXPIRED_ROWS = 400;

    /** 保留期内行数：1 天前写入 */
    private static final int KEPT_ROWS = 5;

    @BeforeAll
    static void initIdWorker() {
        com.yomi.boot.java.base.utils.IdUtils.initialize(1L, 0L);
    }

    static Stream<Arguments> databases() {
        return Stream.of(
                Arguments.of("mysql", "mysql", "yomi.test.mysql.url", "YOMI_TEST_MYSQL_URL"),
                Arguments.of("pg", "pgsql", "yomi.test.pg.url", "YOMI_TEST_PG_URL"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void cleanup_deletesExactlyTheConfiguredBudgetAndStops(String db, String expectedDialect,
                                                          String urlProperty, String envKey) {
        String url = setting(urlProperty, envKey);
        assumeTrue(StringUtils.isNotBlank(url), "未配置 " + urlProperty + " / " + envKey + "，跳过真机用例");

        runner(url, db).run(context -> {
            assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
            assertEquals(expectedDialect, context.getBean(SqlSessionFactory.class).getConfiguration().getDatabaseId(),
                    "方言由 ds 的 DsDatabaseIdProvider 从真库解析");

            DataSource dataSource = context.getBean(DataSource.class);
            IHttpLogRepository repository = context.getBean(IHttpLogRepository.class);
            HttpLogProperties properties = context.getBean(HttpLogProperties.class);
            properties.getRetention().setChunkSize(100);
            properties.getRetention().setMaxDeletePerRun(250);
            HttpLogRetentionCleaner cleaner = new HttpLogRetentionCleaner(properties,
                    context.getBean(HttpLogKeys.class), repository, context.getBean(HttpLogMetrics.class));

            deleteServiceRows(dataSource);
            insertRows(dataSource, EXPIRED_ROWS, 10);
            insertRows(dataSource, KEPT_ROWS, 1);

            Instant oldest = repository.findOldestCreateTime(SERVICE);
            assertEquals(10, Duration.between(oldest, Instant.now()).toDays(), 1, "落后 = 最旧一行的年龄");

            cleaner.clean();

            assertEquals(EXPIRED_ROWS - 250 + KEPT_ROWS, countServiceRows(dataSource),
                    "单轮按上限删 250 行：剩 150 过期 + 5 保留");
            assertEquals(Duration.ofDays(10).toSeconds(), cleaner.getLagSeconds(), 60, "还有过期行，落后不变");

            cleaner.clean();

            assertEquals(KEPT_ROWS, countServiceRows(dataSource), "再跑一轮删空过期行，保留期内行不受影响");
            assertEquals(Duration.ofDays(1).toSeconds(), cleaner.getLagSeconds(), 60, "删空后落后回落到保留区内");

            deleteServiceRows(dataSource);
        });
    }

    /** 真库上下文：ds（真 Hikari 主库）+ httplog；不配 Redis 以避开集群任务 */
    private static ApplicationContextRunner runner(String url, String db) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class,
                        DesensitizeAutoConfiguration.class, HttpLogAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + url,
                        "spring.datasource.username=" + setting("yomi.test." + db + ".username",
                                "YOMI_TEST_" + db.toUpperCase() + "_USERNAME", "admin"),
                        "spring.datasource.password=" + setting("yomi.test." + db + ".password",
                                "YOMI_TEST_" + db.toUpperCase() + "_PASSWORD", ""),
                        "spring.application.name=" + SERVICE,
                        "yomi.httplog.enabled=true",
                        "yomi.httplog.service-name=" + SERVICE,
                        "yomi.httplog.mode=memory",
                        "yomi.httplog.retention.enabled=false");
    }

    /** 写入 count 行：create_time = now - daysAgo 天 */
    private static void insertRows(DataSource dataSource, int count, int daysAgo) throws SQLException {
        String sql = "INSERT INTO t_sys_http_log (id, service_name, request_time, duration_ms, method, uri, status, "
                + "request_body_truncated, response_body_truncated, async_started, create_time, update_time) "
                + "VALUES (?, ?, ?, 1, 'GET', '/it', 200, ?, ?, ?, ?, ?)";
        Instant when = Instant.now().minus(Duration.ofDays(daysAgo));
        long base = 9_000_000_000L + (long) daysAgo * 1_000_000L;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < count; i++) {
                statement.setLong(1, base + i);
                statement.setString(2, SERVICE);
                statement.setTimestamp(3, Timestamp.from(when));
                statement.setBoolean(4, false);
                statement.setBoolean(5, false);
                statement.setBoolean(6, false);
                statement.setTimestamp(7, Timestamp.from(when));
                statement.setTimestamp(8, Timestamp.from(when));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** 该服务当前行数 */
    private static int countServiceRows(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM t_sys_http_log WHERE service_name = ?")) {
            statement.setString(1, SERVICE);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** 清掉用例自己的行（含上一次失败残留） */
    private static void deleteServiceRows(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM t_sys_http_log WHERE service_name = ?")) {
            statement.setString(1, SERVICE);
            statement.executeUpdate();
        }
    }

    private static String setting(String systemProperty, String environmentVariable) {
        return setting(systemProperty, environmentVariable, null);
    }

    private static String setting(String systemProperty, String environmentVariable, String defaultValue) {
        String value = System.getProperty(systemProperty);
        if (StringUtils.isBlank(value)) {
            value = System.getenv(environmentVariable);
        }
        return StringUtils.isBlank(value) ? defaultValue : value;
    }

}
