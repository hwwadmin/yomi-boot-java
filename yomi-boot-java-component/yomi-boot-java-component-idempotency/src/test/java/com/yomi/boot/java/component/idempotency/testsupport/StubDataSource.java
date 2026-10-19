package com.yomi.boot.java.component.idempotency.testsupport;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * 测试用数据源桩：不连库，{@link #getConnection()} 返回无操作的代理连接。
 * <p>
 * 给真实的事务管理器（{@code DataSourceTransactionManager}）用：离线用例要的是"事务绑在哪个数据源上"
 * 这个事实，不需要真库；每个实例身份唯一，正好当线程资源表的键。
 * <p>连接上的 {@code setSavepoint / rollback(Savepoint) / releaseSavepoint / commit / rollback} 会被记账
 * （{@link #events()}），用于断言保存点确实建了、冲突后回滚到保存点、以及事务最终是提交还是整体回滚。
 */
public class StubDataSource implements DataSource {

    /** 连接上的事务事件（方法名；带保存点的回滚记为 rollbackToSavepoint） */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    /** 连接上发生过的事务事件 */
    public List<String> events() {
        return events;
    }

    @Override
    public Connection getConnection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setSavepoint" -> {
                            events.add("setSavepoint");
                            // 必须返回非 null：调用方以"保存点是否为 null"判定有没有建成功
                            return savepoint();
                        }
                        case "releaseSavepoint" -> events.add("releaseSavepoint");
                        case "rollback" -> events.add(args != null && args.length > 0 && args[0] != null
                                ? "rollbackToSavepoint" : "rollback");
                        case "commit" -> events.add("commit");
                        case "getMetaData" -> {
                            // 事务管理器会问连接支不支持保存点（ConnectionHolder.supportsSavepoints）
                            return metaData();
                        }
                        default -> {
                        }
                    }
                    return defaults(proxy, method, args);
                });
    }

    /** 元数据桩：支持保存点（其余方法给默认值） */
    private static DatabaseMetaData metaData() {
        return (DatabaseMetaData) Proxy.newProxyInstance(StubDataSource.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) ->
                        "supportsSavepoints".equals(method.getName()) ? Boolean.TRUE : defaults(proxy, method, args));
    }

    /** 保存点桩 */
    private static Savepoint savepoint() {
        return (Savepoint) Proxy.newProxyInstance(StubDataSource.class.getClassLoader(),
                new Class<?>[]{Savepoint.class}, StubDataSource::defaults);
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
    private static Object defaults(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "stub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> switch (method.getReturnType().getName()) {
                case "boolean" -> false;
                case "int" -> 0;
                case "long" -> 0L;
                default -> null;
            };
        };
    }

}
