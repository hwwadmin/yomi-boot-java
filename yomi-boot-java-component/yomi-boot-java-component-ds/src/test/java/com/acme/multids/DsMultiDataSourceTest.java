package com.acme.multids;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.yomi.boot.java.component.ds.config.DsAutoConfiguration;
import com.yomi.boot.java.component.ds.support.DsCrossDbGuard;
import com.yomi.boot.java.component.ds.support.DsMaster;
import com.yomi.boot.java.component.ds.testmapper.reportstyle.ReportTestMapper;
import com.yomi.boot.java.component.ds.testmapper.masterstyle.ComponentStyleTestMapper;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.plugin.Interceptor;
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
 * 多数据源形态（方案里"业务系统自行装配附加库"的契约）：
 * <p>
 * ① 组件/宿主不写 ref 的 {@code @MapperScan} 落主库（{@code @Primary} 兜底）；
 * ② 业务自建附加库按模板（复用容器的链/TypeHandler/方言 + 挂守卫）装配后，显式绑定的 Mapper 落附加库，
 * 两个库的 Mapper 注册互不串；
 * ③ 只写一半 ref（{@code sqlSessionFactoryRef} 或 {@code sqlSessionTemplateRef}）会被启动自检拦下——
 * MapperFactoryBean 的 factory 与 template 两个属性都会被按类型装配，只写一个会被容器里的主库模板劫持路由。
 */
class DsMultiDataSourceTest {

    /** 主库由 ds 装配 + 业务自建 report 库（模板形态，两个 ref 都写） */
    @Test
    void mappersBindToTheirOwnFactoryAndDoNotCrossLibraries() {
        // 数据源用 withBean 注册（先于配置类注册，自动装配的条件才看得见）；真实应用里 Boot 同样保证自动配置最后解析
        new ApplicationContextRunner()
                .withBean(DsMaster.DATASOURCE_BEAN, DataSource.class, DsMultiDataSourceTest::stubDataSource)
                .withBean("reportDataSource", DataSource.class, DsMultiDataSourceTest::stubDataSource)
                .withUserConfiguration(ReportDbConfig.class, ComponentStyleScan.class, ReportScanBothRefs.class)
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
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

    /** 只写 sqlSessionFactoryRef（plan 里的旧写法）：MapperFactoryBean 的 template 属性会被主库模板劫持 → 启动即失败 */
    @Test
    void singleRefBindingIsRejectedAtStartup() {
        new ApplicationContextRunner()
                .withBean(DsMaster.DATASOURCE_BEAN, DataSource.class, DsMultiDataSourceTest::stubDataSource)
                .withBean("reportDataSource", DataSource.class, DsMultiDataSourceTest::stubDataSource)
                .withUserConfiguration(ReportDbConfig.class, ReportScanFactoryRefOnly.class)
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "只写一半 ref 必须拦启动（否则 Mapper 会静默跑在主库上）");
                    String text = String.valueOf(failure.getMessage());
                    assertTrue(text.contains("sqlSessionTemplateRef"), "报错要给出补法: " + text);
                });
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
     * + 模板 + 事务管理器 —— 与《组件使用说明》的模板一致
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
