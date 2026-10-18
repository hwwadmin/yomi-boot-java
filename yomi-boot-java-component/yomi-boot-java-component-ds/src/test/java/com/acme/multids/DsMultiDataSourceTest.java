package com.acme.multids;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.yomi.boot.java.component.ds.config.DsAutoConfiguration;
import com.yomi.boot.java.component.ds.support.DsCrossDbGuard;
import com.yomi.boot.java.component.ds.support.DsMaster;
import com.yomi.boot.java.component.ds.testsupport.StubDataSource;
import com.yomi.boot.java.component.ds.testmapper.reportstyle.ReportTestMapper;
import com.yomi.boot.java.component.ds.testmapper.masterstyle.ComponentStyleTestMapper;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多数据源形态（业务系统自行装配附加库的契约）：
 * <p>
 * ① 主库只由组件按 yml 装配（{@code masterXxx}），组件/宿主不写 ref 的 {@code @MapperScan} 落主库；
 * ② 业务自建附加库按模板（复用容器的链/TypeHandler/方言 + 挂守卫）装配后，显式绑定的 Mapper 落附加库，
 * 两个库的 Mapper 注册互不串；
 * ③ 只写一半 ref（{@code sqlSessionFactoryRef} 或 {@code sqlSessionTemplateRef}）会被启动自检拦下——
 * MapperFactoryBean 的 factory 与 template 两个属性都按类型装配，只写一个会路由到容器里的主库模板；
 * ④ 附加库那套标 {@code @Primary} 同样拦启动：按类型解析会被它抢走，组件与不写 ref 的 Mapper 都会静默落错库。
 */
class DsMultiDataSourceTest {

    /** 主库由 ds 按 yml 装配 + 业务自建 report 库（模板形态，两个 ref 都写） */
    @Test
    void mappersBindToTheirOwnFactoryAndDoNotCrossLibraries() {
        masterRunner(ReportDbConfig.class, ComponentStyleScan.class, ReportScanBothRefs.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());

                    // 组件形态：不写 ref → 主库
                    SqlSessionFactory componentFactory = mapperFactory(context, "componentStyleTestMapper");
                    assertSame(context.getBean(DsMaster.SQL_SESSION_FACTORY_BEAN),
                            componentFactory, "不写 ref 的 Mapper 必须落主库（@Primary 兜底）");

                    // 业务附加库形态：显式 ref → report
                    SqlSessionFactory reportFactory = mapperFactory(context, "reportTestMapper");
                    assertSame(context.getBean("reportSqlSessionFactory"), reportFactory,
                            "显式绑定的 Mapper 必须落附加库工厂");

                    // 两个库的 Mapper 注册互不串（各自只注册自己那份）
                    assertTrue(reportFactory.getConfiguration().hasMapper(ReportTestMapper.class),
                            "report 工厂应注册 reportTestMapper");
                    assertFalse(reportFactory.getConfiguration().hasMapper(ComponentStyleTestMapper.class),
                            "report 工厂不应注册主库的 Mapper");
                    assertTrue(context.getBean(DsMaster.SQL_SESSION_FACTORY_BEAN, SqlSessionFactory.class)
                                    .getConfiguration().hasMapper(ComponentStyleTestMapper.class),
                            "主库工厂应注册不写 ref 的 Mapper");
                    assertFalse(context.getBean(DsMaster.SQL_SESSION_FACTORY_BEAN, SqlSessionFactory.class)
                                    .getConfiguration().hasMapper(ReportTestMapper.class),
                            "主库工厂不应注册 report 的 Mapper");

                    // 守卫：两个工厂各挂一个，库名对应
                    assertEquals(List.of(DsMaster.LIBRARY_NAME), guardLibraries(componentFactory));
                    assertEquals(List.of("report"), guardLibraries(reportFactory));
                });
    }

    /** 只写 sqlSessionFactoryRef：MapperFactoryBean 的 template 属性会按类型绑到主库模板，启动自检直接失败 */
    @Test
    void singleRefBindingIsRejectedAtStartup() {
        masterRunner(ReportDbConfig.class, ReportScanFactoryRefOnly.class)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "只写一半 ref 必须拦启动（否则 Mapper 会静默跑在主库上）");
                    String text = messages(failure);
                    assertTrue(text.contains("sqlSessionTemplateRef"), "报错要给出补法: " + text);
                });
    }

    /**
     * 附加库工厂标 @Primary：按类型解析被抢走 → 不写 ref 的 Mapper 与组件都会静默落错库。
     * 有 Mapper 在场时按类型注入先失败（Spring 点名两个 @Primary 候选），无 Mapper 时由绑定审计点名。
     */
    @Test
    void additionalLibraryFactoryMustNotBePrimary() {
        masterRunner(PrimaryReportFactory.class, ComponentStyleScan.class)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "附加库工厂标 @Primary 必须拦启动");
                    String text = messages(failure);
                    assertTrue(text.contains("primaryReportSqlSessionFactory")
                                    && text.contains(DsMaster.SQL_SESSION_FACTORY_BEAN),
                            "要点名抢位的工厂与主库工厂: " + text);
                    assertTrue(text.contains("primary"), "要说明是多个 @Primary 的冲突: " + text);
                });
    }

    /** 附加库事务管理器标 @Primary：@Transactional 与组件事务会落到另一个库，启动即拦 */
    @Test
    void additionalLibraryTransactionManagerMustNotBePrimary() {
        masterRunner(PrimaryReportTransactionManager.class)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "附加库事务管理器标 @Primary 必须拦启动");
                    String text = messages(failure);
                    assertTrue(text.contains(DsMaster.TRANSACTION_MANAGER_BEAN), "要点名主库事务管理器: " + text);
                });
    }

    /** 主库只走 yml：用 StubDataSource 当池实现，装配真跑一遍又不连库 */
    private static ApplicationContextRunner masterRunner(Class<?>... userConfigurations) {
        return new ApplicationContextRunner()
                .withBean("reportDataSource", DataSource.class, DsMultiDataSourceTest::stubDataSource)
                .withUserConfiguration(userConfigurations)
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:mysql://127.0.0.1:3306/stub_multids_test",
                        "spring.datasource.type=" + StubDataSource.class.getName());
    }

    /** 失败链上的全部消息（自检异常可能被容器包一层） */
    private static String messages(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append('\n');
        }
        return text.toString();
    }

    /** 取某个 mapper 实际使用的 SqlSessionFactory */
    private static SqlSessionFactory mapperFactory(org.springframework.context.ApplicationContext context,
                                                   String mapperBeanName) {
        MapperFactoryBean<?> factoryBean = context.getBean("&" + mapperBeanName, MapperFactoryBean.class);
        return factoryBean.getSqlSessionFactory();
    }

    /** 工厂上挂的守卫库名 */
    private static List<String> guardLibraries(SqlSessionFactory factory) {
        return factory.getConfiguration().getInterceptors().stream()
                .filter(DsCrossDbGuard.class::isInstance)
                .map(DsCrossDbGuard.class::cast)
                .map(DsCrossDbGuard::libraryName)
                .toList();
    }

    /**
     * 业务自建的附加库：连接（自己的 DataSource）+ 工厂（复用容器的链/TypeHandler/方言 + 挂守卫）
     * + 模板 + 事务管理器 —— 与《组件使用说明》的模板一致；整套都用自己的 Bean 名，不标 @Primary
     */
    @Configuration(proxyBeanMethods = false)
    static class ReportDbConfig {

        @Bean("reportSqlSessionFactory")
        SqlSessionFactory reportSqlSessionFactory(@Qualifier("reportDataSource") DataSource reportDataSource,
                                                  MybatisPlusInterceptor chain,
                                                  List<TypeHandler<?>> typeHandlers,
                                                  DatabaseIdProvider databaseIdProvider) throws Exception {
            var factory = new com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean();
            factory.setDataSource(reportDataSource);
            factory.setPlugins(chain, DsCrossDbGuard.of("report", reportDataSource));
            factory.setTypeHandlers(typeHandlers.toArray(TypeHandler[]::new));
            factory.setDatabaseIdProvider(databaseIdProvider);
            return factory.getObject();
        }

        @Bean("reportSqlSessionTemplate")
        SqlSessionTemplate reportSqlSessionTemplate(
                @Qualifier("reportSqlSessionFactory") SqlSessionFactory reportSqlSessionFactory) {
            return new SqlSessionTemplate(reportSqlSessionFactory);
        }

        @Bean("reportTransactionManager")
        PlatformTransactionManager reportTransactionManager(
                @Qualifier("reportDataSource") DataSource reportDataSource) {
            return new DataSourceTransactionManager(reportDataSource);
        }
    }

    /** 错误形态：附加库工厂标 @Primary（按类型解析会被它抢走） */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryReportFactory {

        @Bean("primaryReportSqlSessionFactory")
        @org.springframework.context.annotation.Primary
        SqlSessionFactory primaryReportSqlSessionFactory(@Qualifier("reportDataSource") DataSource reportDataSource,
                                                         MybatisPlusInterceptor chain,
                                                         List<TypeHandler<?>> typeHandlers,
                                                         DatabaseIdProvider databaseIdProvider) throws Exception {
            var factory = new com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean();
            factory.setDataSource(reportDataSource);
            factory.setPlugins(chain, DsCrossDbGuard.of("report", reportDataSource));
            factory.setTypeHandlers(typeHandlers.toArray(TypeHandler[]::new));
            factory.setDatabaseIdProvider(databaseIdProvider);
            return factory.getObject();
        }
    }

    /** 错误形态：附加库事务管理器标 @Primary */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryReportTransactionManager {

        @Bean("primaryReportTransactionManager")
        @org.springframework.context.annotation.Primary
        PlatformTransactionManager primaryReportTransactionManager(
                @Qualifier("reportDataSource") DataSource reportDataSource) {
            return new DataSourceTransactionManager(reportDataSource);
        }
    }

    /** 组件形态扫描：不写 ref（主库） */
    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.yomi.boot.java.component.ds.testmapper.masterstyle")
    static class ComponentStyleScan {
    }

    /** 附加库扫描：两个 ref 都写（正确形态） */
    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackages = "com.yomi.boot.java.component.ds.testmapper.reportstyle",
            sqlSessionFactoryRef = "reportSqlSessionFactory",
            sqlSessionTemplateRef = "reportSqlSessionTemplate")
    static class ReportScanBothRefs {
    }

    /** 附加库扫描：只写工厂 ref（错误形态，启动自检应拦下） */
    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackages = "com.yomi.boot.java.component.ds.testmapper.reportstyle",
            sqlSessionFactoryRef = "reportSqlSessionFactory")
    static class ReportScanFactoryRefOnly {
    }

    private static DataSource stubDataSource() {
        return (DataSource) Proxy.newProxyInstance(DsMultiDataSourceTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> null);
    }

}
