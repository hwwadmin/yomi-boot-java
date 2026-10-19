package com.yomi.boot.java.component.event.config;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 数据源桩：连接可用、语句无副作用；不触库。
 * 主数据源只能按 ds 的固定名字（masterDataSource）注册，装配测试用它顶主库。
 */
final class ProbeDataSource {

    private ProbeDataSource() {
    }

    static DataSource allTablesExist() {
        Statement statement = (Statement) Proxy.newProxyInstance(ProbeDataSource.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, args) -> defaults(proxy, method, args));
        Connection connection = (Connection) Proxy.newProxyInstance(ProbeDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) ->
                        "createStatement".equals(method.getName()) ? statement : defaults(proxy, method, args));
        return (DataSource) Proxy.newProxyInstance(ProbeDataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) ->
                        "getConnection".equals(method.getName()) ? connection : defaults(proxy, method, args));
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
