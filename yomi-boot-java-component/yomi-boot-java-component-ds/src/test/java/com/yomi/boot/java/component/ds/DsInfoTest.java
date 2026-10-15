package com.yomi.boot.java.component.ds;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.ds.ItemDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 数据源信息展示：名称解析、连接目标格式化、类型展示与占位约定
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
    void routingDataSourceExpandedAndSortedByName() {
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(List.of());
        routing.addDataSource("master", wrapped("master",
                "jdbc:mysql://192.168.110.208:43307/yomi_boot_java?characterEncoding=UTF-8&useSSL=false", "admin"));
        routing.addDataSource("backup", wrapped("backup", "jdbc:postgresql://127.0.0.1:5432/backup", "backup"));

        new DsInfo(List.of(routing)).init();

        // 连接参数被剔除，仅保留 host:port/database；类型取自 url 子协议；ItemDataSource 包装展开为真实连接池
        assertEquals(List.of(
                "数据源连接信息 [backup: 127.0.0.1:5432/backup - backup]，类型: postgresql",
                "数据源连接池类型: com.zaxxer.hikari.HikariDataSource",
                "数据源连接信息 [master: 192.168.110.208:43307/yomi_boot_java - admin]，类型: mysql",
                "数据源连接池类型: com.zaxxer.hikari.HikariDataSource"), messages());
    }

    @Test
    void plainDataSourceNamedByPoolName() {
        new DsInfo(List.of(hikari("report", "jdbc:mysql://127.0.0.1:3306/report", "report"))).init();

        assertEquals(List.of(
                "数据源连接信息 [report: 127.0.0.1:3306/report - report]，类型: mysql",
                "数据源连接池类型: com.zaxxer.hikari.HikariDataSource"), messages());
    }

    @Test
    void nonUrlJdbcUrlKeptAsIs() {
        new DsInfo(List.of(hikari("h2db", "jdbc:h2:mem:test", "sa"))).init();

        assertEquals(List.of(
                "数据源连接信息 [h2db: jdbc:h2:mem:test - sa]，类型: h2",
                "数据源连接池类型: com.zaxxer.hikari.HikariDataSource"), messages());
    }

    @Test
    void unavailableTargetAndUsernameUsePlaceholder() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("empty");

        new DsInfo(List.of(dataSource)).init();

        assertEquals(List.of(
                "数据源连接信息 [empty: ~ - ~]，类型: ~",
                "数据源连接池类型: com.zaxxer.hikari.HikariDataSource"), messages());
    }

    @Test
    void emptyDataSourceListLogsDisabled() {
        new DsInfo(List.of()).init();

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

    /** dynamic-datasource 实际注册的形状：真实连接池被 ItemDataSource 包装 */
    private static ItemDataSource wrapped(String name, String jdbcUrl, String username) {
        HikariDataSource hikari = hikari(name, jdbcUrl, username);
        return new ItemDataSource(name, hikari, hikari, false, false, null);
    }

    /** 收集启动日志消息 */
    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

}
