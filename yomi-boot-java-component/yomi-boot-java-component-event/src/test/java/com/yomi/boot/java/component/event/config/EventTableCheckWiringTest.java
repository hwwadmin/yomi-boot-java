package com.yomi.boot.java.component.event.config;

import com.yomi.boot.java.component.ds.support.DsMaster;
import com.yomi.boot.java.component.ds.support.TableInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * event 启动期建表检查的接线验证：发件箱侧覆盖 outbox + dlq，收件箱侧覆盖 inbox。
 * <p>轻量反射式（不启动整个上下文）：直接调用装配类的 @Bean 方法并执行检查，
 * 数据源按主库名（masterDataSource）注册，从 stub DataSource 上记录实际执行过的表名
 * （stub 全部表都存在 → 只探活、不建表）。
 */
class EventTableCheckWiringTest {

    @Test
    void publisherInitializerCoversOutboxTables() {
        List<String> executedSql = new ArrayList<>();
        TableInitializer initializer = new EventAutoConfiguration.PublisherConfiguration()
                .eventOutboxTableInitializer(beanFactoryOf(recordingDataSource(executedSql)));

        initializer.afterSingletonsInstantiated();

        assertEquals(Set.of("sys_event_outbox", "sys_event_outbox_dlq"), tables(executedSql),
                "发件箱侧表清单必须与 sql/mysql|pgsql/sys_event.sql 一致");
    }

    @Test
    void consumerInitializerCoversInboxTable() {
        List<String> executedSql = new ArrayList<>();
        TableInitializer initializer = new EventAutoConfiguration.ConsumerConfiguration()
                .eventInboxTableInitializer(beanFactoryOf(recordingDataSource(executedSql)));

        initializer.afterSingletonsInstantiated();

        assertEquals(Set.of("sys_event_inbox"), tables(executedSql),
                "收件箱侧表清单必须与 sql/mysql|pgsql/sys_event.sql 一致");
    }

    /** 声明事件组件依赖的表名 */
    private static Set<String> tables(List<String> executedSql) {
        return executedSql.stream()
                .map(sql -> sql.substring("SELECT 1 FROM ".length(), sql.indexOf(" WHERE")))
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 记录执行过的 SQL、并假定所有表都存在 */
    private static DataSource recordingDataSource(List<String> executedSql) {
        return (DataSource) Proxy.newProxyInstance(EventTableCheckWiringTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return null;
                    }
                    return Proxy.newProxyInstance(EventTableCheckWiringTest.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (conn, connMethod, connArgs) -> {
                                if (!"createStatement".equals(connMethod.getName())) {
                                    return null;
                                }
                                return (Statement) Proxy.newProxyInstance(
                                        EventTableCheckWiringTest.class.getClassLoader(),
                                        new Class<?>[]{Statement.class}, (stmt, stmtMethod, stmtArgs) -> {
                                            if ("executeQuery".equals(stmtMethod.getName())) {
                                                executedSql.add((String) stmtArgs[0]);
                                            }
                                            return null;
                                        });
                            });
                });
    }

    /** 主库形态的 BeanFactory：数据源注册成 masterDataSource（建表只认主库名） */
    private static ListableBeanFactory beanFactoryOf(DataSource dataSource) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton(DsMaster.DATASOURCE_BEAN, dataSource);
        return beanFactory;
    }

}
