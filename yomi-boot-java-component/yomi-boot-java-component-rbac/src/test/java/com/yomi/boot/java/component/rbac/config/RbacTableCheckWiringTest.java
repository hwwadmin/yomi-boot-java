package com.yomi.boot.java.component.rbac.config;

import com.yomi.boot.java.component.ds.support.TableInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * rbac 启动期建表检查的接线验证：检查器必须覆盖全部 6 张表。
 * <p>轻量反射式（不启动整个上下文）：直接调用装配类的 @Bean 方法并执行检查，
 * 从 stub DataSource 上记录实际执行过的表名（stub 全部表都存在 → 只探活、不建表）。
 */
class RbacTableCheckWiringTest {

    @Test
    void initializerCoversAllSixRbacTables() {
        List<String> executedSql = new ArrayList<>();
        TableInitializer initializer = new RbacAutoConfiguration()
                .rbacTableInitializer(providerOf(recordingDataSource(executedSql)));

        initializer.afterSingletonsInstantiated();

        Set<String> tables = executedSql.stream()
                .map(sql -> sql.substring("SELECT 1 FROM ".length(), sql.indexOf(" WHERE")))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("sys_rbac_perm", "sys_rbac_perm_group", "sys_rbac_role",
                "sys_rbac_user_role_rel", "sys_rbac_role_group_rel", "sys_rbac_group_perm_rel"), tables,
                "表清单必须与 sql/mysql|pgsql/sys_rbac.sql 完全一致");
    }

    /** 记录执行过的 SQL、并假定所有表都存在 */
    private static DataSource recordingDataSource(List<String> executedSql) {
        return (DataSource) Proxy.newProxyInstance(RbacTableCheckWiringTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return null;
                    }
                    return Proxy.newProxyInstance(RbacTableCheckWiringTest.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (conn, connMethod, connArgs) -> {
                                if (!"createStatement".equals(connMethod.getName())) {
                                    return null;
                                }
                                return (Statement) Proxy.newProxyInstance(
                                        RbacTableCheckWiringTest.class.getClassLoader(),
                                        new Class<?>[]{Statement.class}, (stmt, stmtMethod, stmtArgs) -> {
                                            if ("executeQuery".equals(stmtMethod.getName())) {
                                                executedSql.add((String) stmtArgs[0]);
                                            }
                                            return null;
                                        });
                            });
                });
    }

    /** 将单例包装为 ObjectProvider */
    private static ObjectProvider<DataSource> providerOf(DataSource dataSource) {
        return new ObjectProvider<>() {
            @Override
            public DataSource getObject() throws BeansException {
                return dataSource;
            }

            @Override
            public DataSource getObject(Object... args) throws BeansException {
                return dataSource;
            }

            @Override
            public DataSource getIfAvailable() throws BeansException {
                return dataSource;
            }

            @Override
            public DataSource getIfUnique() throws BeansException {
                return dataSource;
            }

            @Override
            public Stream<DataSource> stream() {
                return Stream.of(dataSource);
            }

            @Override
            public void forEach(Consumer<? super DataSource> action) {
                action.accept(dataSource);
            }

            @Override
            public java.util.Iterator<DataSource> iterator() {
                return List.of(dataSource).iterator();
            }
        };
    }

}
