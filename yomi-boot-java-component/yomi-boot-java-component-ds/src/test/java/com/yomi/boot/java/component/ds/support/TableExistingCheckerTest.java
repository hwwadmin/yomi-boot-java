package com.yomi.boot.java.component.ds.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动期建表检查：表齐全放行、缺表 fail-fast、无法解析数据源时跳过（不误伤）。
 * 全程离线：stub DataSource/Connection/Statement 按表名模拟"存在/不存在"。
 */
class TableExistingCheckerTest {

    @Test
    void passesWhenAllTablesExist() {
        List<String> executed = new ArrayList<>();
        TableExistingChecker checker = new TableExistingChecker(
                providerOf(stubDataSource(Set.of("t_a", "t_b"), executed)),
                "测试组件", "sql/test.sql", List.of("t_a", "t_b"));

        checker.afterSingletonsInstantiated();

        assertEquals(2, executed.size(), "每张表都应执行一次存在性检查");
        assertTrue(executed.stream().allMatch(sql -> sql.startsWith("SELECT 1 FROM ")),
                "检查语句应为 WHERE 1=0 的轻量校验: " + executed);
        assertTrue(executed.stream().allMatch(sql -> sql.endsWith(" WHERE 1 = 0")), "不应扫描任何行: " + executed);
    }

    @Test
    void failsFastWhenTableMissing() {
        TableExistingChecker checker = new TableExistingChecker(
                providerOf(stubDataSource(Set.of("t_a"), new ArrayList<>())),
                "测试组件", "sql/test.sql", List.of("t_a", "t_missing"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);

        assertTrue(ex.getMessage().contains("缺少表"), "报错必须说明缺少表: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("t_missing"), "报错必须点名缺失的表: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("sql/test.sql"), "报错必须给出建表 SQL 路径: " + ex.getMessage());
    }

    @Test
    void logsTableNamesInsteadOfCount() {
        TableExistingChecker checker = new TableExistingChecker(
                providerOf(stubDataSource(Set.of("t_a", "t_b"), new ArrayList<>())),
                "测试组件", "sql/test.sql", List.of("t_a", "t_b"));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(TableExistingChecker.class);
        logger.addAppender(appender);
        try {
            checker.afterSingletonsInstantiated();
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertTrue(logs.contains("t_a") && logs.contains("t_b"), "通过日志必须列出表名: " + logs);
        assertFalse(logs.contains("张"), "通过日志不应输出表的数量: " + logs);
    }

    @Test
    void failsFastWhenConnectionUnavailable() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("connection refused");
                    }
                    return defaultReturn(proxy, method, args);
                });
        TableExistingChecker checker = new TableExistingChecker(providerOf(broken),
                "测试组件", "sql/test.sql", List.of("t_a"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("无法获取数据库连接"), ex.getMessage());
    }

    @Test
    void skipsWhenNoDataSourceResolvable() {
        // 多候选且无 @Primary、也无 dynamic-datasource 路由数据源：无法判定主库，告警跳过而不是误判失败
        ObjectProvider<DataSource> ambiguous = new StubProvider<>(List.of(stubDataSource(Set.of(), new ArrayList<>()),
                stubDataSource(Set.of(), new ArrayList<>())));
        TableExistingChecker checker = new TableExistingChecker(ambiguous, "测试组件", "sql/test.sql", List.of("t_a"));

        checker.afterSingletonsInstantiated();
    }

    /** 模拟建表 SQL：目标表不在 existingTables 中时抛"表不存在" */
    private static DataSource stubDataSource(Set<String> existingTables, List<String> executedSql) {
        return (DataSource) Proxy.newProxyInstance(TableExistingCheckerTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return defaultReturn(proxy, method, args);
                    }
                    return Proxy.newProxyInstance(TableExistingCheckerTest.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (conn, connMethod, connArgs) -> {
                                if (!"createStatement".equals(connMethod.getName())) {
                                    return defaultReturn(conn, connMethod, connArgs);
                                }
                                return stubStatement(existingTables, executedSql);
                            });
                });
    }

    /** 构造 JDBC Statement 桩 */
    private static Statement stubStatement(Set<String> existingTables, List<String> executedSql) {
        return (Statement) Proxy.newProxyInstance(TableExistingCheckerTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (stmt, method, args) -> {
                    if (!"executeQuery".equals(method.getName())) {
                        return defaultReturn(stmt, method, args);
                    }
                    String sql = (String) args[0];
                    executedSql.add(sql);
                    String table = sql.substring("SELECT 1 FROM ".length(), sql.indexOf(" WHERE"));
                    if (!existingTables.contains(table)) {
                        throw new SQLException("Table '" + table + "' doesn't exist");
                    }
                    return null;
                });
    }

    /** 未知方法返回默认值（close 等生命周期方法为无操作），仅 Object 方法给出合理实现 */
    private static Object defaultReturn(Object proxy, java.lang.reflect.Method method, Object[] args) {
        switch (method.getName()) {
            case "toString" -> {
                return "stub";
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            }
            default -> {
                return null;
            }
        }
    }

    /** 将单例包装为 ObjectProvider */
    private static ObjectProvider<DataSource> providerOf(DataSource dataSource) {
        return new StubProvider<>(List.of(dataSource));
    }

    /** 最小 ObjectProvider 实现：只支持 getIfUnique 与 stream */
    private static final class StubProvider<T> implements ObjectProvider<T> {

        private final List<T> beans;

        private StubProvider(List<T> beans) {
            this.beans = beans;
        }

        @Override
        public T getObject() throws BeansException {
            return getIfUnique();
        }

        @Override
        public T getObject(Object... args) throws BeansException {
            return getIfUnique();
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return beans.isEmpty() ? null : beans.get(0);
        }

        @Override
        public T getIfUnique() throws BeansException {
            return beans.size() == 1 ? beans.get(0) : null;
        }

        @Override
        public Stream<T> stream() {
            return beans.stream();
        }

        @Override
        public void forEach(Consumer<? super T> action) {
            beans.forEach(action);
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return beans.iterator();
        }
    }

}
