package com.yomi.boot.java.component.ds.testsupport;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.logging.Logger;

/**
 * 测试用主数据源桩：主数据源只走 yml 装配（宿主不得声明 masterDataSource），
 * 所以测试通过 {@code spring.datasource.url} + {@code spring.datasource.type=本类} 让组件真的去装配。
 * <p>
 * url 用 MySQL 写法（{@code DataSourceProperties} 要能推导出驱动类名），但不建真实连接：
 * {@link #getConnection()} 返回按 {@code productName} 回答元数据的代理连接，
 * 供方言解析（{@code DsDatabaseIdProvider}）与预热（{@code DsPreheat}）离线跑通。
 * 属性经 {@code spring.datasource.stub.*}（池参数推导前缀）绑定。
 */
public class StubDataSource implements DataSource {

    private String url;

    /** 元数据里的数据库产品名（决定 databaseId：MySQL/PG） */
    private String productName = "MySQL";

    /** 任意池参数（验证"池参数按推导前缀绑定"） */
    private int size;

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    /** DataSourceProperties 会按 url 推导驱动类名并设置（本桩不连库，收下即弃） */
    public void setDriverClassName(String driverClassName) {
    }

    public void setUsername(String username) {
    }

    public void setPassword(String password) {
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    @Override
    public Connection getConnection() {
        InvocationHandler metaDataHandler = (metaData, method, args) ->
                "getDatabaseProductName".equals(method.getName()) ? productName : defaults(metaData, method, args, true);
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, metaDataHandler);
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (connection, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metaData;
                    // 预热用的 JDBC 标准探活
                    case "isValid" -> Boolean.TRUE;
                    default -> defaults(connection, method, args, true);
                });
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("stub-datasource");
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
        throw new UnsupportedOperationException("stub datasource");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }

    /** 原始类型必须给合法默认值（代理返回 null 会 NPE），生命周期方法当无操作 */
    private static Object defaults(Object proxy, Method method, Object[] args, boolean silent) {
        return switch (method.getName()) {
            case "toString" -> "stub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) yield false;
                if (type == int.class) yield 0;
                if (type == long.class) yield 0L;
                if (type == short.class) yield (short) 0;
                if (type == byte.class) yield (byte) 0;
                if (type == double.class) yield 0d;
                if (type == float.class) yield 0f;
                if (type == char.class) yield (char) 0;
                yield null;
            }
        };
    }

}
