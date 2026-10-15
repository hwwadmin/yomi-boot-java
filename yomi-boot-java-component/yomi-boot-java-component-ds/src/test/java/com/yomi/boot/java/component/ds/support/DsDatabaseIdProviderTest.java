package com.yomi.boot.java.component.ds.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方言标识解析：MySQL→{@code mysql}、PostgreSQL→{@code pgsql}（与 {@code sql/mysql|pgsql} 目录同名）；
 * 库连上但方言不认识必须启动即失败（父类此时会静默返回 null，那样方言语句不会注册）；
 * 解析结果会在启动日志里展示（数据源类型）。
 */
class DsDatabaseIdProviderTest {

    private final DsDatabaseIdProvider provider = new DsDatabaseIdProvider();

    @Test
    void mapsSupportedDialectsToScriptDirectoryNames() {
        assertEquals("mysql", provider.getDatabaseId(productName("MySQL")));
        assertEquals("pgsql", provider.getDatabaseId(productName("PostgreSQL")));
    }

    /** 启动日志必须展示解析出的方言（运维可见的"数据源类型"） */
    @Test
    void logsResolvedDialectAtStartup() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(DsDatabaseIdProvider.class);
        appender.start();
        logger.addAppender(appender);
        try {
            provider.getDatabaseId(productName("PostgreSQL"));

            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(message -> message.contains("数据源方言: pgsql")),
                    "启动日志应展示数据源方言: " + appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void failsFastOnUnsupportedDialectWithProductNameInMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> provider.getDatabaseId(productName("Oracle")));

        assertTrue(ex.getMessage().contains("Oracle"), ex.getMessage());
        assertTrue(ex.getMessage().contains("MySQL / PostgreSQL"), ex.getMessage());
    }

    /** 库连不上（无数据源宿主 / 库未就绪）不阻断启动：方言语句不注册，DB 能力本来就不启用 */
    @Test
    void returnsNullWhenConnectionCannotBeObtained() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("db down");
                    }
                    return defaults(proxy, method, args);
                });

        assertNull(provider.getDatabaseId(broken));
    }

    // ===== 夹具：只回答"产品名"的 DataSource 代理 =====

    private static DataSource productName(String name) {
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
                DsDatabaseIdProviderTest.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> "getDatabaseProductName".equals(method.getName())
                        ? name : defaults(proxy, method, args));
        Connection connection = (Connection) Proxy.newProxyInstance(
                DsDatabaseIdProviderTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> "getMetaData".equals(method.getName())
                        ? metaData : defaults(proxy, method, args));
        return (DataSource) Proxy.newProxyInstance(DsDatabaseIdProviderTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> "getConnection".equals(method.getName())
                        ? connection : defaults(proxy, method, args));
    }

    private static Object defaults(Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "stub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
    }

}
