package com.yomi.boot.java.component.ds.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主库解析规则：只认 component-ds 装配的固定名字（{@code masterXxx}），缺失即启动失败并点名候选与出路。
 * 不做"唯一/@Primary 兜底"——那是"业务自带库顶主库"的形态；@Primary 只影响按类型注入。
 * 事务模板按"绑定主库事务管理器"判定（按实例同一性，唯一性不等于正确性）。
 */
class DsMasterTest {

    /** 主库设施按名字取：即使别的数据源标了 @Primary，主库只能是 masterDataSource */
    @Test
    void requireDataSourceTakesTheMasterNamedBean() {
        new ApplicationContextRunner()
                .withBean(DsMaster.DATASOURCE_BEAN, DataSource.class, DsMasterTest::stubDataSource)
                .withBean("reportDataSource", DataSource.class, DsMasterTest::stubDataSource)
                .run(context -> assertSame(context.getBean(DsMaster.DATASOURCE_BEAN),
                        DsMaster.requireDataSource(context.getBeanFactory())));
    }

    /** 只认名字：别的名字的数据源不算主库（附加库形态），失败并给出候选与出路 */
    @Test
    void requireDataSourceFailsWithoutMasterNamedBean() {
        new ApplicationContextRunner()
                .withBean("dataSource", DataSource.class, DsMasterTest::stubDataSource)
                .run(context -> {
                    IllegalStateException ex = assertThrows(IllegalStateException.class,
                            () -> DsMaster.requireDataSource(context.getBeanFactory()));
                    assertTrue(ex.getMessage().contains(DsMaster.DATASOURCE_BEAN), "要点名主库 Bean: " + ex.getMessage());
                    assertTrue(ex.getMessage().contains("dataSource"), "要列出候选: " + ex.getMessage());
                    assertTrue(ex.getMessage().contains("spring.datasource.url"), "要给出出路: " + ex.getMessage());
                });
    }

    /** 单事务管理器 + 绑它的事务模板：取到该模板 */
    @Test
    void requireTransactionTemplateTakesTemplateOnMaster() {
        PlatformTransactionManager master = manager();
        withMasterManager(master)
                .withBean(TransactionTemplate.class, () -> new TransactionTemplate(master))
                .run(context -> assertEquals(context.getBean(DsMaster.TRANSACTION_MANAGER_BEAN),
                        DsMaster.requireTransactionTemplate(context.getBeanFactory()).getTransactionManager()));
    }

    /**
     * 唯一但绑错库：宿主为别的库声明模板会让 Boot 的标准模板退让，唯一性照样会命中它——
     * 必须失败，否则组件写库落在不属于业务事务的连接上
     */
    @Test
    void requireTransactionTemplateFailsWhenSoleTemplateIsBoundToAnotherLibrary() {
        withMasterManager(manager())
                .withBean("reportTransactionTemplate", TransactionTemplate.class,
                        () -> new TransactionTemplate(manager()))
                .run(context -> {
                    IllegalStateException ex = assertThrows(IllegalStateException.class,
                            () -> DsMaster.requireTransactionTemplate(context.getBeanFactory()));
                    assertTrue(ex.getMessage().contains("reportTransactionTemplate"),
                            "要点名绑错库的模板: " + ex.getMessage());
                    assertTrue(ex.getMessage().contains(DsMaster.TRANSACTION_MANAGER_BEAN),
                            "要点名主库事务管理器: " + ex.getMessage());
                });
    }

    /** 多模板、只有一个绑主库：取绑主库的那个（旧判据"候选不唯一"会误杀这种正确形态） */
    @Test
    void requireTransactionTemplatePicksTheMasterBoundOneAmongMany() {
        PlatformTransactionManager master = manager();
        withMasterManager(master)
                .withBean("reportTransactionTemplate", TransactionTemplate.class,
                        () -> new TransactionTemplate(manager()))
                .withBean("masterLibraryTemplate", TransactionTemplate.class,
                        () -> new TransactionTemplate(master))
                .run(context -> assertSame(context.getBean("masterLibraryTemplate"),
                        DsMaster.requireTransactionTemplate(context.getBeanFactory())));
    }

    /** 多个都绑主库：@Primary 优先 */
    @Test
    void requireTransactionTemplatePrefersPrimaryAmongMasterBound() {
        PlatformTransactionManager master = manager();
        withMasterManager(master)
                .withBean("txA", TransactionTemplate.class, () -> new TransactionTemplate(master))
                .withBean("txB", TransactionTemplate.class, () -> new TransactionTemplate(master),
                        definition -> definition.setPrimary(true))
                .run(context -> assertSame(context.getBean("txB"),
                        DsMaster.requireTransactionTemplate(context.getBeanFactory())));
    }

    /** 多个都绑主库、没有 @Primary：Boot 标准名 transactionTemplate 优先 */
    @Test
    void requireTransactionTemplatePrefersStandardNameAmongMasterBound() {
        PlatformTransactionManager master = manager();
        withMasterManager(master)
                .withBean("txA", TransactionTemplate.class, () -> new TransactionTemplate(master))
                .withBean(DsMaster.TRANSACTION_TEMPLATE_BEAN, TransactionTemplate.class,
                        () -> new TransactionTemplate(master))
                .run(context -> assertSame(context.getBean(DsMaster.TRANSACTION_TEMPLATE_BEAN),
                        DsMaster.requireTransactionTemplate(context.getBeanFactory())));
    }

    /** 多个都绑主库且无 @Primary、也没有标准名：无法判定，失败点名 */
    @Test
    void requireTransactionTemplateFailsWhenAmbiguousOnMaster() {
        PlatformTransactionManager master = manager();
        withMasterManager(master)
                .withBean("txA", TransactionTemplate.class, () -> new TransactionTemplate(master))
                .withBean("txB", TransactionTemplate.class, () -> new TransactionTemplate(master))
                .run(context -> {
                    IllegalStateException ex = assertThrows(IllegalStateException.class,
                            () -> DsMaster.requireTransactionTemplate(context.getBeanFactory()));
                    assertTrue(ex.getMessage().contains("txA") && ex.getMessage().contains("txB"),
                            "要点名候选: " + ex.getMessage());
                    assertTrue(ex.getMessage().contains("@Primary"), "要给出出路: " + ex.getMessage());
                });
    }

    /** 主库没有任何模板：失败并给出路（Boot 的标准模板为何会缺席要说清） */
    @Test
    void requireTransactionTemplateFailsWhenNoneExists() {
        withMasterManager(manager())
                .run(context -> {
                    IllegalStateException ex = assertThrows(IllegalStateException.class,
                            () -> DsMaster.requireTransactionTemplate(context.getBeanFactory()));
                    assertTrue(ex.getMessage().contains("TransactionTemplate"), ex.getMessage());
                    assertTrue(ex.getMessage().contains("自行声明"), "要给出出路: " + ex.getMessage());
                });
    }

    /** 主库事务管理器 + 主库模板都用同一个实例（判定按实例同一性） */
    private static ApplicationContextRunner withMasterManager(PlatformTransactionManager master) {
        return new ApplicationContextRunner()
                .withBean(DsMaster.TRANSACTION_MANAGER_BEAN, PlatformTransactionManager.class, () -> master);
    }

    private static PlatformTransactionManager manager() {
        return new DataSourceTransactionManager(stubDataSource());
    }

    private static DataSource stubDataSource() {
        return (DataSource) Proxy.newProxyInstance(DsMasterTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> null);
    }

}
