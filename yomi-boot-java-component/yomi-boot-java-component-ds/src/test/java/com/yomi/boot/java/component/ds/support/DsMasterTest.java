package com.yomi.boot.java.component.ds.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主库解析规则：{@code masterDataSource} → {@code dataSource} → 唯一/{@code @Primary} → 启动失败（不猜）。
 * 宿主接管主库（按约定名提供）是受支持形态；多候选且无 @Primary 必须点名候选。
 */
class DsMasterTest {

    @Test
    void prefersMasterNamedBean() {
        new ApplicationContextRunner()
                .withBean(DsMaster.DATASOURCE_BEAN, DataSource.class, DsMasterTest::stubDataSource)
                .withBean("dataSource", DataSource.class, DsMasterTest::stubDataSource)
                .run(context -> assertEquals(DsMaster.DATASOURCE_BEAN,
                        DsMaster.resolveDataSource(context.getBeanFactory()).beanName()));
    }

    /** 宿主想接管主库：用同名 Bean 顶替 */
    @Test
    void hostBeanWithMasterNameIsAdopted() {
        new ApplicationContextRunner()
                .withBean(DsMaster.DATASOURCE_BEAN, DataSource.class, DsMasterTest::stubDataSource)
                .run(context -> assertEquals(DsMaster.DATASOURCE_BEAN,
                        DsMaster.resolveDataSource(context.getBeanFactory()).beanName()));
    }

    /** 没有 masterXxx 命名时退回"唯一候选" */
    @Test
    void fallsBackToSingleCandidate() {
        new ApplicationContextRunner()
                .withBean("onlyDataSource", DataSource.class, DsMasterTest::stubDataSource)
                .run(context -> assertEquals("",
                        DsMaster.resolveDataSource(context.getBeanFactory()).beanName()));
    }

    /** 多候选且无 @Primary：启动失败并点名候选，不静默挑一个 */
    @Test
    void failsFastWhenAmbiguous() {
        new ApplicationContextRunner()
                .withBean("firstDataSource", DataSource.class, DsMasterTest::stubDataSource)
                .withBean("secondDataSource", DataSource.class, DsMasterTest::stubDataSource)
                .run(context -> {
                    IllegalStateException ex = assertThrows(IllegalStateException.class,
                            () -> DsMaster.resolveDataSource(context.getBeanFactory()));
                    assertTrue(ex.getMessage().contains("firstDataSource") && ex.getMessage().contains("secondDataSource"),
                            "要点名候选: " + ex.getMessage());
                    assertTrue(ex.getMessage().contains("@Primary"), "要给出出路: " + ex.getMessage());
                });
    }

    private static DataSource stubDataSource() {
        return (DataSource) Proxy.newProxyInstance(DsMasterTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> null);
    }

}
