package com.yomi.boot.java.component.ds;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 数据源信息展示：Bean 名做展示名、连接目标格式化、类型展示与占位约定，
 * 覆盖容器内全部数据源（含业务自建的附加库）
 */
class DsInfoTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(DsInfo.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void listsAllDataSourcesSortedByBeanName() {
        Map<String, DataSource> beans = new LinkedHashMap<>();
        beans.put("reportDataSource", hikari("report", "jdbc:postgresql://127.0.0.1:5432/backup", "backup"));
        beans.put("masterDataSource",
                hikari("master", "jdbc:mysql://192.168.110.208:43307/yomi_boot_java?characterEncoding=UTF-8", "admin"));

        new DsInfo(beans).init();

        // 按 Bean 名排序输出；连接参数被剔除，仅保留 host:port/database；类型取自 url 子协议
        assertEquals(List.of(
                "数据源连接信息 [masterDataSource: 192.168.110.208:43307/yomi_boot_java - admin]，类型: mysql",
                "数据源连接池类型: com.zaxxer.hikari.HikariDataSource",
                "数据源连接信息 [reportDataSource: 127.0.0.1:5432/backup - backup]，类型: postgresql",
                "数据源连接池类型: com.zaxxer.hikari.HikariDataSource"), messages());
    }

    @Test
    void nonUrlJdbcUrlKeptAsIs() {
        new DsInfo(Map.of("h2DataSource", hikari("h2db", "jdbc:h2:mem:test", "sa"))).init();

        assertEquals(List.of(
                "数据源连接信息 [h2DataSource: jdbc:h2:mem:test - sa]，类型: h2",
                "数据源连接池类型: com.zaxxer.hikari.HikariDataSource"), messages());
    }

    @Test
    void unavailableTargetAndUsernameUsePlaceholder() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("empty");

        new DsInfo(Map.of("emptyDataSource", dataSource)).init();

        assertEquals(List.of(
                "数据源连接信息 [emptyDataSource: ~ - ~]，类型: ~",
                "数据源连接池类型: com.zaxxer.hikari.HikariDataSource"), messages());
    }

    @Test
    void emptyDataSourceMapLogsDisabled() {
        new DsInfo(Map.of()).init();

        assertEquals(List.of("数据源配置未启用."), messages());
    }

    /** 构造 Hikari 数据源描述 */
    private static HikariDataSource hikari(String poolName, String jdbcUrl, String username) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName(poolName);
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(username);
        return dataSource;
    }

    /** 收集启动日志消息 */
    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

}
