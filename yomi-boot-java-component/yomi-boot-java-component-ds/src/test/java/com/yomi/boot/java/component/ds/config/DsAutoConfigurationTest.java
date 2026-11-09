package com.yomi.boot.java.component.ds.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.yomi.boot.java.component.ds.DsInfo;
import com.yomi.boot.java.component.ds.DsPreheat;
import com.yomi.boot.java.component.ds.support.DsCrossDbGuard;
import com.yomi.boot.java.component.ds.support.DsDatabaseIdProvider;
import com.yomi.boot.java.component.ds.support.DsMaster;
import com.yomi.boot.java.component.ds.support.InstantJdbcTypeHandler;
import com.yomi.boot.java.component.ds.support.MybatisPlusChainChecker;
import com.yomi.boot.java.component.ds.testsupport.StubDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ds 自动装配验证：宿主不扫描 com.yomi 时（仅靠 imports 清单）主库四件套（{@code masterXxx} + {@code @Primary}）
 * 与预热/信息 Bean 也能注册；主数据源只走 yml 装配、业务声明 {@code masterXxx} 即拦启动；无数据源宿主不装配主库。
 * 全部离线运行（stub DataSource，{@link DsPreheat} 预热失败仅记日志不阻断）。
 */
class DsAutoConfigurationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** 桩 URL：能用 MySQL 写法就够（只推导驱动类名，不建连接） */
    private static final String STUB_URL = "jdbc:mysql://127.0.0.1:3306/stub_ds_test";

    /** 主库只走 yml 装配：用 {@link StubDataSource} 当池实现，装配真跑一遍又不连库 */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + STUB_URL,
                        "spring.datasource.type=" + StubDataSource.class.getName());
    }

    /** 失败链上的全部消息（条件里抛的异常会被容器包一层） */
    private static String rootMessage(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    void importsFileDeclaresAutoConfiguration() throws IOException {
        String content = readImportsResource();
        assertTrue(content.contains(DsAutoConfiguration.class.getName()),
                "imports 清单须声明 DsAutoConfiguration");
    }

    @Test
    void registersPaginationInterceptorAndLifecycleBeans() {
        runner()
                .run(context -> {
                    assertNotNull(context.getBean(MybatisPlusInterceptor.class), "应装配分页插件");
                    assertNotNull(context.getBean(DsPreheat.class));
                    assertNotNull(context.getBean(DsInfo.class));
                });
    }

    /** 主库四件套由组件按 yml 装配，全部 @Primary（不指定名称的注入都落主库） */
    @Test
    void masterBeansArePrimaryAndBoundToMasterDataSource() {
        runner()
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    DataSource masterDataSource = context.getBean(DsMaster.DATASOURCE_BEAN, DataSource.class);
                    assertInstanceOf(StubDataSource.class, masterDataSource,
                            "主数据源按 spring.datasource.type 装配（只走 yml）");
                    assertSame(masterDataSource, context.getBean(DsMaster.SQL_SESSION_FACTORY_BEAN,
                                    SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource(),
                            "主库工厂必须绑主数据源");
                    assertTrue(notPrimary(context, DsMaster.DATASOURCE_BEAN, DsMaster.SQL_SESSION_FACTORY_BEAN,
                                    DsMaster.SQL_SESSION_TEMPLATE_BEAN, DsMaster.TRANSACTION_MANAGER_BEAN).isEmpty(),
                            "主库四件套应全部 @Primary，缺: "
                                    + notPrimary(context, DsMaster.DATASOURCE_BEAN, DsMaster.SQL_SESSION_FACTORY_BEAN,
                                    DsMaster.SQL_SESSION_TEMPLATE_BEAN, DsMaster.TRANSACTION_MANAGER_BEAN));
                    assertNotNull(context.getBean(DsMaster.SQL_SESSION_TEMPLATE_BEAN, SqlSessionTemplate.class));
                    assertNotNull(context.getBean(DsMaster.TRANSACTION_MANAGER_BEAN, PlatformTransactionManager.class));
                });
    }

    /** 配了 spring.datasource.url：主数据源由本组件声明（默认 Hikari），池名用库名 master */
    @Test
    void masterDataSourceIsDeclaredWhenUrlConfigured() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:mysql://127.0.0.1:3306/ds_auto_config_test",
                        "spring.datasource.username=admin")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    DataSource dataSource = context.getBean(DsMaster.DATASOURCE_BEAN, DataSource.class);
                    assertInstanceOf(HikariDataSource.class, dataSource, "缺省连接池是 Hikari");
                    assertEquals("jdbc:mysql://127.0.0.1:3306/ds_auto_config_test",
                            ((HikariDataSource) dataSource).getJdbcUrl());
                    assertEquals("master", ((HikariDataSource) dataSource).getPoolName(), "未配 pool-name 时用库名做池名");
                    assertTrue(context.getBeanFactory().getBeanDefinition(DsMaster.DATASOURCE_BEAN).isPrimary(),
                            "主数据源必须 @Primary");
                });
    }

    /** 连接池类型可换：spring.datasource.type 指定实现，池参数按推导前缀 spring.datasource.<池名>.* 绑定 */
    @Test
    void masterDataSourceTypeCanBeReplaced() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/ds_auto_config_test",
                        "spring.datasource.username=admin",
                        "spring.datasource.type=" + PoolDataSource.class.getName(),
                        "spring.datasource.pool.size=7")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    DataSource dataSource = context.getBean(DsMaster.DATASOURCE_BEAN, DataSource.class);
                    assertInstanceOf(PoolDataSource.class, dataSource, "spring.datasource.type 指定的连接池实现必须生效");
                    assertEquals(7, ((PoolDataSource) dataSource).getSize(),
                            "池参数应按推导前缀 spring.datasource.pool.* 绑定");
                });
    }

    /** 池参数前缀推导：简单名去掉 DataSource 后缀、首字母小写（Hikari 时与 Boot 的 spring.datasource.hikari 一致） */
    @Test
    void poolPropertiesPrefixIsDerivedFromType() {
        assertEquals("spring.datasource.hikari", MasterDataSourceConfiguration.poolPropertiesPrefix(HikariDataSource.class));
        assertEquals("spring.datasource.druid", MasterDataSourceConfiguration.poolPropertiesPrefix(DruidDataSource.class));
        assertEquals("spring.datasource.pool", MasterDataSourceConfiguration.poolPropertiesPrefix(PoolDataSource.class));
    }

    /** 测试用 Druid 同名类（只为验证前缀推导，不引 Druid 依赖） */
    static class DruidDataSource extends HikariDataSource {
    }

    /** 测试用连接池实现（DataSourceBuilder 需要无参构造 + setter） */
    public static class PoolDataSource implements DataSource {

        private int size;

        private String url;

        public void setSize(int size) {
            this.size = size;
        }

        public int getSize() {
            return size;
        }

        /** DataSourceBuilder 按 url/username/password/driverClassName 约定映射（与 Boot 的 Generic 路径一致） */
        public void setUrl(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }

        public void setUsername(String username) {
        }

        public void setPassword(String password) {
        }

        public void setDriverClassName(String driverClassName) {
        }

        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("stub pool");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("stub pool");
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
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("stub-pool");
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("stub pool");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    /** 没配数据源也没宿主 Bean：完全不装配主库（宿主可以只用 ds 的工具类） */
    @Test
    void assemblesWithoutAnyDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class))
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            () -> "无数据源时不应启动失败: " + context.getStartupFailure());
                    assertFalse(context.containsBean(DsMaster.SQL_SESSION_FACTORY_BEAN), "不应凭空装配工厂");
                    assertFalse(context.containsBean(DsMaster.TRANSACTION_MANAGER_BEAN), "不应凭空装配事务管理器");
                });
    }

    /** 主数据源由本组件独占：业务声明 masterXxx 就是"替换主数据源的装配流程"，启动失败并给出路 */
    @Test
    void hostDeclaredMasterBeansAreRejected() {
        runner()
                .withBean(DsMaster.SQL_SESSION_FACTORY_BEAN, SqlSessionFactory.class,
                        () -> stubSqlSessionFactory(stubDataSource(), true))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "宿主声明 masterSqlSessionFactory 必须拦启动");
                    String text = rootMessage(failure);
                    assertTrue(text.contains(DsMaster.SQL_SESSION_FACTORY_BEAN), "要点名占用的 Bean: " + text);
                    assertTrue(text.contains("spring.datasource.url"), "要给出出路: " + text);
                });

        runner()
                .withBean(DsMaster.DATASOURCE_BEAN, DataSource.class, DsAutoConfigurationTest::stubDataSource)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "宿主声明 masterDataSource 必须拦启动");
                    assertTrue(rootMessage(failure).contains(DsMaster.DATASOURCE_BEAN),
                            "要点名占用的 Bean: " + rootMessage(failure));
                });
    }

    /** 主库四件套必须带分页链：按 Page 取批的语句靠它加 LIMIT */
    @Test
    void masterFactoryCarriesPaginationAndBlockAttack() {
        runner()
                .run(context -> {
                    SqlSessionFactory factory = context.getBean("masterSqlSessionFactory", SqlSessionFactory.class);
                    List<String> plugins = factory.getConfiguration().getInterceptors().stream()
                            .map(it -> it.getClass().getName()).toList();
                    List<String> inner = innerInterceptors(factory).stream()
                            .map(it -> it.getClass().getName()).toList();
                    assertTrue(inner.contains(PaginationInnerInterceptor.class.getName()), "缺分页: " + inner);
                    assertTrue(inner.contains(BlockAttackInnerInterceptor.class.getName()), "缺防全表: " + inner);
                    assertEquals(PaginationInnerInterceptor.class.getName(), inner.get(0),
                            "顺序遵循 MP 建议：会改写 SQL 的分页在前");
                    assertTrue(plugins.contains(DsCrossDbGuard.class.getName()), "主库工厂应自动挂跨库守卫: " + plugins);
                });
    }

    /** 宿主注册的 InnerInterceptor Bean 收进链（链 Bean 由本组件装配，MP 不组装默认链） */
    @Test
    void hostProvidedInnerInterceptorIsCollected() {
        Class<?> hostType = HostInterceptor.class;
        runner()
                .withBean(InnerInterceptor.class, HostInterceptor::new)
                .run(context -> assertTrue(interceptorClasses(context).contains(hostType.getName()),
                        "宿主自定义拦截器未被装配: " + interceptorClasses(context)));
    }

    /** 宿主注册了与内置同类的拦截器：跳过并告警，同类只保留一份（重复挂载会双重解析/双重执行） */
    @Test
    void hostInterceptorOfSameBuiltInClassIsSkipped() {
        runner()
                .withBean(BlockAttackInnerInterceptor.class, BlockAttackInnerInterceptor::new)
                .run(context -> assertEquals(1,
                        interceptorClasses(context).stream()
                                .filter(name -> name.equals(BlockAttackInnerInterceptor.class.getName()))
                                .count(),
                        "同类拦截器只能有一份: " + interceptorClasses(context)));
    }

    /** 宿主自管链必须自带分页：缺分页时链自检直接拦启动（组件里按 Page 取批的语句依赖它加 LIMIT） */
    @Test
    void failsFastWhenHostChainMissesPagination() {
        runner()
                .withBean(MybatisPlusInterceptor.class, MybatisPlusInterceptor::new)
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "缺分页必须拦启动");
                    assertTrue(context.getStartupFailure().getMessage().contains("PaginationInnerInterceptor"),
                            "报错要给出补分页的出路: " + context.getStartupFailure().getMessage());
                });
    }

    /** 分页与防全表写随 ds 组件强制生效：pagination.enabled=false 也关不掉任何一项 */
    @Test
    void paginationCannotBeDisabledByProperty() {
        runner()
                .withPropertyValues("yomi.ds.pagination.enabled=false")
                .run(context -> {
                    assertNotNull(context.getBean(MybatisPlusInterceptor.class), "分页与防全表写无开关，设 false 也必须装配");
                    assertTrue(interceptorClasses(context).contains(PaginationInnerInterceptor.class.getName()),
                            "分页缺失: " + interceptorClasses(context));
                    assertTrue(interceptorClasses(context).contains(BlockAttackInnerInterceptor.class.getName()),
                            "防全表写缺失: " + interceptorClasses(context));
                    assertNotNull(context.getBean(MybatisPlusChainChecker.class), "链自检同样无开关");
                });
    }

    @Test
    void paginationDialectIsAutoDetectedByDefault() {
        runner()
                .run(context -> assertNull(paginationDialect(context),
                        "默认必须按连接自动探测方言（多数据源/多方言各自正确），不得写死 MySQL"));
    }

    @Test
    void paginationDialectCanBePinnedByProperty() {
        runner()
                .withPropertyValues("yomi.ds.pagination.db-type=postgre_sql")
                .run(context -> assertEquals(DbType.POSTGRE_SQL, paginationDialect(context),
                        "显式指定（大小写不敏感）应生效"));
    }

    @Test
    void invalidPaginationDialectFailsFast() {
        runner()
                .withPropertyValues("yomi.ds.pagination.db-type=mysql8")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "拼错的方言值应导致启动失败");
                    String text = String.valueOf(failure.getMessage());
                    assertTrue(text.contains("yomi.ds.pagination.db-type"), text);
                    assertTrue(text.contains("POSTGRE_SQL"), "报错应给出合法取值提示: " + text);
                });
    }

    /** 启动日志打出方言来源：自动探测或显式指定（多方言排查看这条） */
    @Test
    void paginationDialectIsLoggedForBothModes() {
        String autoLogs = startupLogsOfPagination(null);
        assertTrue(autoLogs.contains("分页方言: 自动探测"), autoLogs);
        assertTrue(autoLogs.contains("yomi.ds.pagination.db-type"), "应提示固定方言的配置键: " + autoLogs);

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
            ApplicationContextRunner runner = runner();
            if (dbType != null) {
                runner = runner.withPropertyValues("yomi.ds.pagination.db-type=" + dbType);
            }
            runner.run(context -> assertNotNull(context.getBean(MybatisPlusInterceptor.class)));
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    /** 主数据源公告只打一次（工厂与事务管理器都会解析主数据源，公告不能跟着解析重复） */
    @Test
    void masterDataSourceIsAnnouncedExactlyOnce() {
        List<String> announcements = announcementsOf(runner());
        assertEquals(1, announcements.size(), "主数据源公告应只打一次: " + announcements);
        assertTrue(announcements.get(0).contains("masterDataSource（component-ds 装配）"), announcements.get(0));
    }

    /** 业务自带的数据源（非主库名）只是附加库：主库仍是组件装配的那套，公告也只认主库 */
    @Test
    void hostProvidedDataSourceIsJustAnAdditionalLibrary() {
        List<String> announcements = announcementsOf(runner()
                .withBean("reportDataSource", DataSource.class, DsAutoConfigurationTest::stubDataSource));
        assertEquals(1, announcements.size(), "主数据源公告应只打一次: " + announcements);
        assertTrue(announcements.get(0).contains("masterDataSource（component-ds 装配）"), announcements.get(0));
    }

    /** 附加库那套标了 @Primary：按类型解析会被它抢走（不写 ref 的 Mapper、@Transactional 都落错库）→ 绑定审计拦启动 */
    @Test
    void additionalLibraryMustNotBePrimary() {
        runner()
                .withBean("reportDataSource", DataSource.class, DsAutoConfigurationTest::stubDataSource,
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "附加库标 @Primary 必须拦启动（否则组件静默查错库）");
                    String text = rootMessage(failure);
                    assertTrue(text.contains("reportDataSource"), "要点名抢位的 Bean: " + text);
                    assertTrue(text.contains("@Primary"), "要给出出路: " + text);
                });
    }

    /**
     * 跑一次上下文并收集"主数据源 = ?"公告：appender 挂在 ds 包级 Logger 上，
     * 无论公告由哪个类打出都计入，计数才有判别力
     */
    private static List<String> announcementsOf(ApplicationContextRunner runner) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger("com.yomi.boot.java.component.ds");
        logger.addAppender(appender);
        try {
            runner.run(context -> {
                assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                assertNotNull(context.getBean(DsMaster.TRANSACTION_MANAGER_BEAN, PlatformTransactionManager.class),
                        "事务管理器要真的装配出来，公告计数才有意义");
            });
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("[ds] 主数据源 =")).toList();
    }

    /** 防全表更新/删除：默认必须进链（BlockAttack 拦截无有效条件的 UPDATE/DELETE） */
    @Test
    void blockAttackIsAlwaysInChain() {
        runner()
                .run(context -> {
                    assertTrue(interceptorClasses(context).contains(BlockAttackInnerInterceptor.class.getName()),
                            "BlockAttack 缺失时一条漏写 WHERE 的 delete 就能清空整表: " + interceptorClasses(context));
                    assertEquals(PaginationInnerInterceptor.class.getName(), interceptorClasses(context).get(0),
                            "顺序遵循 MP 建议：会改写 SQL 的分页在前");
                });
    }

    /** 跨库守卫开关：默认 error（开），非法值启动失败 */
    @Test
    void transactionGuardSwitch() {
        runner()
                .withPropertyValues("yomi.ds.transaction-guard=off")
                .run(context -> assertNull(context.getStartupFailure(), "off 是合法值"));

        runner()
                .withPropertyValues("yomi.ds.transaction-guard=abort")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "非法值应启动失败");
                    assertTrue(context.getStartupFailure().getMessage().contains("yomi.ds.transaction-guard"),
                            context.getStartupFailure().getMessage());
                });
    }

    @Test
    void preheatCanBeDisabledByProperty() {
        runner()
                .withPropertyValues("yomi.ds.preheat.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("dsPreheat"));
                    assertNotNull(context.getBean(DsInfo.class), "预热关闭不应影响数据源信息 Bean");
                });
    }

    /**
     * 宿主扫描 com.yomi 场景：会直接扫到自动装配类本身，同时 imports 清单也会注册它。
     * 同一配置类双通道注册时去重，@ConditionalOnMissingBean 生效后每种 Bean 只有一份
     */
    @Test
    void componentScanAndImportsRegisterExactlyOneInstanceOfEachBean() {
        runner()
                .withUserConfiguration(ScanBootstrap.class)
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

    /**
     * 方言提供者必须装配，且真的被本组件注入主库工厂（方言语句靠它选择；删了 Bean 就该红）；
     * MP 的自动配置同时在场时工厂仍由本组件装配
     */
    @Test
    void databaseIdProviderWiresIntoMasterSqlSessionFactory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DsAutoConfiguration.class,
                        MybatisPlusAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + STUB_URL,
                        "spring.datasource.type=" + StubDataSource.class.getName(),
                        "spring.datasource.stub.product-name=MySQL")
                .run(context -> {
                    assertNull(context.getStartupFailure(), "上下文应启动成功");
                    assertNotNull(context.getBean(DatabaseIdProvider.class), "应装配 DsDatabaseIdProvider");
                    SqlSessionFactory factory = context.getBean(DsMaster.SQL_SESSION_FACTORY_BEAN, SqlSessionFactory.class);
                    assertEquals("mysql", factory.getConfiguration().getDatabaseId(),
                            "方言标识必须进到 MyBatis 配置里，mapper XML 的 databaseId 变体才选得出来");
                    assertTrue(factory.getConfiguration().getTypeHandlerRegistry()
                                    .getTypeHandler(Instant.class) instanceof InstantJdbcTypeHandler,
                            "Instant 写库处理器必须注册（PG 驱动不接受 setObject(Instant)）");
                    assertFalse(context.containsBean("sqlSessionFactory"),
                            "MP 的工厂 Bean 应让位给 masterSqlSessionFactory（名字不同也按类型让位）");
                });
    }

    /** 宿主自定义 DatabaseIdProvider 时用宿主那份 */
    @Test
    void hostDatabaseIdProviderWins() {
        runner()
                .withBean(DatabaseIdProvider.class, HostDatabaseIdProvider::new)
                .run(context -> assertTrue(context.getBean(DatabaseIdProvider.class) instanceof HostDatabaseIdProvider));
    }

    /** 宿主自定义方言提供者（探针） */
    static class HostDatabaseIdProvider extends DsDatabaseIdProvider {
    }

    /** 宿主自定义拦截器（非内置类） */
    static class HostInterceptor implements InnerInterceptor {
    }

    /** 宿主自管链必须自带分页：缺分页时链自检直接拦启动 */
    @Configuration(proxyBeanMethods = false)
    static class UserBeans {

        @Bean
        MybatisPlusInterceptor mybatisPlusInterceptor() {
            MybatisPlusInterceptor chain = new MybatisPlusInterceptor();
            chain.addInnerInterceptor(new PaginationInnerInterceptor());
            chain.addInnerInterceptor(new BlockAttackInnerInterceptor());
            return chain;
        }
    }

    /** 取给定 Bean 里实际装配了但未标 @Primary 的（空集合 = 已装配的全部 @Primary） */
    private static List<String> notPrimary(org.springframework.context.ConfigurableApplicationContext context,
                                           String... beanNames) {
        org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        return List.of(beanNames).stream()
                .filter(beanFactory::containsBeanDefinition)
                .filter(name -> !beanFactory.getBeanDefinition(name).isPrimary())
                .toList();
    }

    private static List<String> interceptorClasses(org.springframework.context.ApplicationContext context) {
        return context.getBean(MybatisPlusInterceptor.class).getInterceptors().stream()
                .map(inner -> inner.getClass().getName())
                .toList();
    }

    private static List<InnerInterceptor> innerInterceptors(SqlSessionFactory factory) {
        return factory.getConfiguration().getInterceptors().stream()
                .filter(MybatisPlusInterceptor.class::isInstance)
                .map(MybatisPlusInterceptor.class::cast)
                .flatMap(chain -> chain.getInterceptors().stream())
                .toList();
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

    /** 读取 AutoConfiguration.imports 资源内容 */
    private static String readImportsResource() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(IMPORTS_RESOURCE)) {
            assertNotNull(in, "缺少 " + IMPORTS_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 空 stub：{@link DsPreheat} 预热失败只记日志、{@link DsInfo} 对非 Hikari 数据源不调用任何方法，
     * 因此这里不需要真实连接
     */
    private static DataSource stubDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                DsAutoConfigurationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("stub datasource");
                });
    }

    /** 建工厂用的最小 SqlSessionFactory 桩（不连库）；withChain=顺带挂上分页 + 防全表写与 Instant 处理器（主库工厂的硬校验） */
    private static SqlSessionFactory stubSqlSessionFactory(DataSource dataSource, boolean withChain) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("stub",
                new org.apache.ibatis.transaction.managed.ManagedTransactionFactory(), dataSource));
        if (withChain) {
            MybatisPlusInterceptor chain = new MybatisPlusInterceptor();
            chain.addInnerInterceptor(new PaginationInnerInterceptor());
            chain.addInnerInterceptor(new BlockAttackInnerInterceptor());
            configuration.addInterceptor(chain);
            configuration.getTypeHandlerRegistry().register(Instant.class, new InstantJdbcTypeHandler());
        }
        return new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
    }

    private static Object stubDefaults(Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "stub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
    }

}
