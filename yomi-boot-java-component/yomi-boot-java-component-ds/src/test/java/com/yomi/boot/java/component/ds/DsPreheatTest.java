package com.yomi.boot.java.component.ds;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预热探活：走 JDBC 标准 {@code isValid}（零方言），Oracle 与达梦的 Oracle 兼容模式不接受无 FROM 的 SELECT；
 * isValid 在这两类库上都能正常探活。
 * 预热失败或连接不可用都不阻断启动。
 */
class DsPreheatTest {

    @Test
    void preheatUsesJdbcStandardValidationInsteadOfSql() {
        AtomicBoolean validCalled = new AtomicBoolean();
        AtomicInteger statements = new AtomicInteger();
        DsPreheat preheat = new DsPreheat(Map.of("masterDataSource", dataSource(validCalled, statements, true)));

        preheat.preheat();

        assertTrue(validCalled.get(), "必须走 JDBC 标准探活（isValid）");
        assertEquals(0, statements.get(), "不得再执行 SQL（Oracle 不接受无 FROM 的 SELECT）");
    }

    @Test
    void preheatToleratesInvalidConnectionAndConnectionFailure() {
        // isValid 返回 false：预热判定失败但不抛
        assertDoesNotThrow(() -> new DsPreheat(Map.of(
                "masterDataSource", dataSource(new AtomicBoolean(), new AtomicInteger(), false))).preheat());
        // 连 getConnection 都失败：只记 ERROR，不阻断启动
        DataSource broken = (DataSource) Proxy.newProxyInstance(DsPreheatTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("连接池不可用: " + method.getName());
                });
        assertDoesNotThrow(() -> new DsPreheat(Map.of("brokenDataSource", broken)).preheat());
    }

    /** 伪数据源：记录 isValid/createStatement 是否被调用 */
    private static DataSource dataSource(AtomicBoolean validCalled, AtomicInteger statements, boolean valid) {
        Connection connection = (Connection) Proxy.newProxyInstance(DsPreheatTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isValid" -> {
                        validCalled.set(true);
                        yield valid;
                    }
                    case "createStatement" -> {
                        statements.incrementAndGet();
                        yield null;
                    }
                    case "close" -> null;
                    case "toString" -> "stub-connection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> false;
                    default -> defaultValue(method.getReturnType());
                });
        return (DataSource) Proxy.newProxyInstance(DsPreheatTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    case "toString" -> "stub-datasource";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

}
