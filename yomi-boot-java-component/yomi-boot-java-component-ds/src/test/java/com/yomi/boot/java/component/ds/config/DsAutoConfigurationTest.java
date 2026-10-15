package com.yomi.boot.java.component.ds.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.yomi.boot.java.component.ds.DsInfo;
import com.yomi.boot.java.component.ds.DsPreheat;
import com.yomi.boot.java.component.ds.support.MybatisPlusChainChecker;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ds 自动装配验证：宿主不扫描 com.yomi 时（仅靠 imports 清单）分页插件与
 * 预热/信息 Bean 也能注册；宿主自带同名 Bean 时自动装配让位。
 * 全部离线运行（stub DataSource，{@link DsPreheat} 预热失败仅记日志不阻断）。
 */
class DsAutoConfigurationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void importsFileDeclaresAutoConfiguration() throws IOException {
        String content = readImportsResource();
        assertTrue(content.contains(DsAutoConfiguration.class.getName()),
                "imports 清单须声明 DsAutoConfiguration");
    }

    @Test
    void registersPaginationInterceptorAndLifecycleBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .run(context -> {
                    assertNotNull(context.getBean(MybatisPlusInterceptor.class),
                            "应装配分页插件");
                    assertNotNull(context.getBean(DsPreheat.class));
                    assertNotNull(context.getBean(DsInfo.class));
                });
    }

    @Test
    void backsOffWhenUserDefinesOwnBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withUserConfiguration(UserBeans.class)
                .run(context -> {
                    assertNotNull(context.getBean(MybatisPlusInterceptor.class));
                    assertNotNull(context.getBean(DsPreheat.class));
                    assertNotNull(context.getBean(DsInfo.class));
                    // 自动装配让位后容器里只有用户定义的那一份
                    assertTrue(context.getBeansOfType(MybatisPlusInterceptor.class).size() == 1);
                    assertTrue(context.getBeansOfType(DsPreheat.class).size() == 1);
                    assertTrue(context.getBeansOfType(DsInfo.class).size() == 1);
                });
    }

    /** 分页与防全表写随 ds 组件强制生效：旧开关（pagination.enabled=false）不得再关掉任何一项 */
    @Test
    void paginationCannotBeDisabledByProperty() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .withPropertyValues("yomi.boot.ds.pagination.enabled=false")
                .run(context -> {
                    assertNotNull(context.getBean(MybatisPlusInterceptor.class),
                            "分页与防全表写无开关，设 false 也必须装配");
                    assertTrue(interceptorClasses(context).contains(PaginationInnerInterceptor.class.getName()),
                            "分页缺失: " + interceptorClasses(context));
                    assertTrue(interceptorClasses(context).contains(BlockAttackInnerInterceptor.class.getName()),
                            "防全表写缺失: " + interceptorClasses(context));
                    assertNotNull(context.getBean(MybatisPlusChainChecker.class), "链自检同样无开关");
                });
    }

    @Test
    void paginationDialectIsAutoDetectedByDefault() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .run(context -> assertNull(paginationDialect(context),
                        "默认必须按连接自动探测方言（多数据源/多方言各自正确），不得写死 MySQL"));
    }

    @Test
    void paginationDialectCanBePinnedByProperty() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .withPropertyValues("yomi.boot.ds.pagination.db-type=postgre_sql")
                .run(context -> assertEquals(DbType.POSTGRE_SQL, paginationDialect(context),
                        "显式指定（大小写不敏感）应生效"));
    }

    @Test
    void invalidPaginationDialectFailsFast() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .withPropertyValues("yomi.boot.ds.pagination.db-type=mysql8")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "拼错的方言值应导致启动失败");
                    String text = String.valueOf(failure.getMessage());
                    assertTrue(text.contains("yomi.boot.ds.pagination.db-type"), text);
                    assertTrue(text.contains("POSTGRE_SQL"), "报错应给出合法取值提示: " + text);
                });
    }

    /** 启动日志必须把方言来源打出来：自动探测 vs 显式指定（多方言排查的第一现场） */
    @Test
    void paginationDialectIsLoggedForBothModes() {
        String autoLogs = startupLogsOfPagination(null);
        assertTrue(autoLogs.contains("分页方言: 自动探测"), autoLogs);
        assertTrue(autoLogs.contains("yomi.boot.ds.pagination.db-type"), "应提示固定方言的配置键: " + autoLogs);

        String pinnedLogs = startupLogsOfPagination("POSTGRE_SQL");
        assertTrue(pinnedLogs.contains("分页方言: POSTGRE_SQL"), pinnedLogs);
        assertTrue(pinnedLogs.contains("db-type 指定"), "应说明来源于显式配置: " + pinnedLogs);
    }

    /** 截取分页插件相关启动日志 */
    private static String startupLogsOfPagination(String dbType) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(MybatisPlusConfig.class);
        logger.addAppender(appender);
        try {
            ApplicationContextRunner runner = new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                    .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource);
            if (dbType != null) {
                runner = runner.withPropertyValues("yomi.boot.ds.pagination.db-type=" + dbType);
            }
            runner.run(context -> assertNotNull(context.getBean(MybatisPlusInterceptor.class)));
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    /** 防全表更新/删除：默认必须进链（BlockAttack 拦截无有效条件的 UPDATE/DELETE） */
    @Test
    void blockAttackIsAlwaysInChain() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .run(context -> {
                    assertTrue(interceptorClasses(context).contains(BlockAttackInnerInterceptor.class.getName()),
                            "BlockAttack 缺失时一条漏写 WHERE 的 delete 就能清空整表: " + interceptorClasses(context));
                    assertEquals(PaginationInnerInterceptor.class.getName(), interceptorClasses(context).get(0),
                            "顺序遵循 MP 建议：会改写 SQL 的分页在前");
                });
    }

    /**
     * 宿主注册的 InnerInterceptor Bean 必须被收进链：
     * MP 自带的自动组装带 @ConditionalOnMissingBean(MybatisPlusInterceptor)，本组件在场时它恒让位
     */
    @Test
    void hostProvidedInnerInterceptorIsCollected() {
        Class<?> hostType = HostInterceptor.class;
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .withBean(InnerInterceptor.class, HostInterceptor::new)
                .run(context -> assertTrue(interceptorClasses(context).contains(hostType.getName()),
                        "宿主自定义拦截器未被装配: " + interceptorClasses(context)));
    }

    /** 宿主注册了与内置同类的拦截器：跳过并告警，绝不重复执行（重复挂载会双重解析/双重执行） */
    @Test
    void hostInterceptorOfSameBuiltInClassIsSkipped() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .withBean(BlockAttackInnerInterceptor.class, BlockAttackInnerInterceptor::new)
                .run(context -> assertEquals(1,
                        interceptorClasses(context).stream()
                                .filter(name -> name.equals(BlockAttackInnerInterceptor.class.getName()))
                                .count(),
                        "同类拦截器只能有一份: " + interceptorClasses(context)));
    }

    private static java.util.List<String> interceptorClasses(
            org.springframework.context.ApplicationContext context) {
        return context.getBean(MybatisPlusInterceptor.class).getInterceptors().stream()
                .map(inner -> inner.getClass().getName())
                .toList();
    }

    /** 宿主自定义拦截器（非内置类） */
    static class HostInterceptor implements InnerInterceptor {
    }

    /** 没有 DataSource 的宿主（如只借用 ds 的工具类）：装配不应失败——验收 List<DataSource> 注入的空集合行为 */
    @Test
    void assemblesWithoutAnyDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .run(context -> assertNull(context.getStartupFailure(),
                        () -> "无数据源时不应启动失败: " + context.getStartupFailure()));
    }

    /** 取内部分页拦截器的方言配置：null = 自动探测模式 */
    private static DbType paginationDialect(org.springframework.context.ApplicationContext context) {
        MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
        PaginationInnerInterceptor pagination = interceptor.getInterceptors().stream()
                .filter(PaginationInnerInterceptor.class::isInstance)
                .map(PaginationInnerInterceptor.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 PaginationInnerInterceptor"));
        return pagination.getDbType();
    }

    @Test
    void preheatCanBeDisabledByProperty() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .withPropertyValues("yomi.boot.ds.preheat.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("dsPreheat"));
                    assertNotNull(context.getBean(DsInfo.class), "预热关闭不应影响数据源信息 Bean");
                });
    }

    /**
     * quick-starter 场景：宿主扫描 com.yomi（会直接扫到自动装配类本身），同时 imports 清单也会注册它。
     * 同一配置类双通道必须去重，且自动装配的 @ConditionalOnMissingBean 兜底让位后不产生第二份 Bean
     */
    @Test
    void componentScanAndImportsRegisterExactlyOneInstanceOfEachBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withUserConfiguration(ScanBootstrap.class)
                .withBean(DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .run(context -> {
                    assertNotNull(context.getBean(MybatisPlusInterceptor.class));
                    assertTrue(context.getBeansOfType(MybatisPlusInterceptor.class).size() == 1,
                            "双通道下分页插件只能有一份");
                    assertTrue(context.getBeansOfType(DsInfo.class).size() == 1);
                    assertTrue(context.getBeansOfType(DsPreheat.class).size() == 1);
                });
    }

    @ComponentScan("com.yomi.boot.java.component.ds")
    @Configuration(proxyBeanMethods = false)
    static class ScanBootstrap {
    }

    /** 读取 AutoConfiguration.imports 资源内容 */
    private static String readImportsResource() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(IMPORTS_RESOURCE)) {
            assertNotNull(in, "缺少 " + IMPORTS_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserBeans {

        @Bean
        MybatisPlusInterceptor mybatisPlusInterceptor() {
            return new MybatisPlusInterceptor();
        }

        @Bean
        DsPreheat dsPreheat() {
            return new DsPreheat(List.of());
        }

        @Bean
        DsInfo dsInfo() {
            return new DsInfo(List.of());
        }
    }

    /**
     * 空 stub：{@link DsPreheat} 预热失败只记日志、{@link DsInfo} 对非 Hikari 数据源不调用任何方法，
     * 因此这里不需要真实连接
     */
    private static DataSource stubDataSource() {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DsAutoConfigurationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("stub datasource");
                });
    }

}
