package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.idempotency.core.IdempotencyGuard;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事务模板解析契约：组件不注册事务模板，只用应用自己的——容器里有 （Boot 标准
 * {@code transactionTemplate} / 宿主自定义）就原样复用，但模板必须<b>绑定主库事务管理器</b>
 * （唯一性不等于正确性：别的库的模板会顶掉 Boot 的标准模板，唯一性照样命中它）。
 * 缺失、绑错库、或同一主库 TM 上无法判定用哪个，都启动失败并点名候选（事务基础设施属于应用，组件不自行创建）
 */
class IdempotencyTransactionTemplateWiringTest {

    /** 默认应用：守卫复用容器里的 Boot 标准 transactionTemplate，容器里不出现组件注册的模板 Bean */
    @Test
    void defaultAppReusesBootStandardTemplate() {
        runner(MasterManagerCase.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
            assertArrayEquals(new String[]{"transactionTemplate"},
                    context.getBeanNamesForType(TransactionTemplate.class),
                    "组件不得注册事务模板：容器里应只有应用/ Boot 的模板");
            TransactionTemplate template = context.getBean(TransactionTemplate.class);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, template.getPropagationBehavior());
            assertSame(template, guardTemplate(context), "守卫必须用容器里的模板（占位、业务与写回同一事务）");
            assertSame(context.getBean("masterTransactionManager"), template.getTransactionManager());
        });
    }

    /** 宿主自定义模板（REQUIRES_NEW 等语义）：原样复用，不改写、不另建 */
    @Test
    void hostTemplateIsReusedAsIs() {
        runner(MasterManagerCase.class, HostTemplateCase.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
            assertArrayEquals(new String[]{"hostTransactionTemplate"},
                    context.getBeanNamesForType(TransactionTemplate.class),
                    "宿主模板存在时组件不得再注册自己的模板");
            TransactionTemplate host = context.getBean("hostTransactionTemplate", TransactionTemplate.class);
            assertSame(host, guardTemplate(context), "宿主模板语义必须原样带到守卫");
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, host.getPropagationBehavior());
        });
    }

    /** 多库宿主：主库管理器（masterTransactionManager）+ 附加库管理器 → 用绑主库的那个模板 */
    @Test
    void multiLibraryHostBindsToMasterManager() {
        runner(MasterManagerCase.class, ReportManagerCase.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
            assertSame(context.getBean("masterTransactionManager"),
                    context.getBean(TransactionTemplate.class).getTransactionManager(),
                    "多事务管理器时组件必须用主库事务管理器（Component 默认走主数据源）");
            assertSame(context.getBean(TransactionTemplate.class), guardTemplate(context));
        });
    }

    /**
     * 唯一模板但绑附加库：Boot 标准模板被别的库的模板顶掉后唯一性会命中它——
     * 必须失败（占位记录会写在一个不属于业务事务的连接上）
     */
    @Test
    void templateOffMasterFailsFast() {
        runner(MasterManagerCase.class, ReportManagerCase.class, ReportTemplateCase.class).run(context -> {
            assertNotNull(context.getStartupFailure(), "模板绑附加库必须启动失败");
            Throwable root = rootCause(context.getStartupFailure());
            assertInstanceOf(IllegalStateException.class, root,
                    () -> "必须是组件的事务模板校验失败: " + failureText(context.getStartupFailure()));
            assertTrue(root.getMessage().contains("reportTransactionTemplate"),
                    "要指出是哪个模板绑错库: " + root.getMessage());
            assertTrue(root.getMessage().contains("masterTransactionManager"),
                    "要点名主库事务管理器: " + root.getMessage());
        });
    }

    /** 主库 + 附加库各一个模板且都不 @Primary：应当取主库那份（旧判据"候选不唯一"会误杀） */
    @Test
    void masterBoundTemplateIsPickedAmongMany() {
        runner(MasterManagerCase.class, ReportManagerCase.class, MasterAndReportTemplatesCase.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
                    assertSame(context.getBean("masterLibraryTemplate"), guardTemplate(context),
                            "多模板时应按绑定关系取主库那份，而不是因候选不唯一而失败");
                });
    }

    /** 同一主库 TM 上多个模板且无 @Primary、也不是标准名：无法判定，失败点名候选 */
    @Test
    void ambiguousTemplatesFailFastInsteadOfPickingOne() {
        runner(MasterManagerCase.class, AmbiguousTemplateCase.class).run(context -> {
            assertNotNull(context.getStartupFailure(),
                    "多候选模板无 @Primary 必须启动失败：静默挑一个可能丢掉宿主为守卫准备的传播语义");
            Throwable root = rootCause(context.getStartupFailure());
            assertInstanceOf(IllegalStateException.class, root,
                    () -> "必须是组件的事务模板校验失败: " + failureText(context.getStartupFailure()));
            assertTrue(root.getMessage().contains("hostTxA") && root.getMessage().contains("hostTxB"),
                    "报错必须点名候选: " + root.getMessage());
            assertTrue(failureText(context.getStartupFailure()).contains("IdempotencyAutoConfiguration"),
                    "报错必须指向本组件的装配点: " + failureText(context.getStartupFailure()));
        });
    }

    /** 多个模板都绑主库、其中一个 @Primary：用 @Primary 那个 */
    @Test
    void primaryTemplateWinsAmongCandidates() {
        runner(MasterManagerCase.class, PrimaryTemplateCase.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
            assertSame(context.getBean("hostPrimaryTemplate"), guardTemplate(context),
                    "多候选时应取 @Primary，不拒绝");
        });
    }

    /** 宿主没装配主库事务管理器（只声明了别的名字的管理器）：启动失败点名候选与出路 */
    @Test
    void missingMasterManagerFailsFastInsteadOfPickingOne() {
        runner(NoMasterManagerCase.class).run(context -> {
            assertNotNull(context.getStartupFailure(),
                    "没有主库事务管理器必须启动失败：静默挑一个正是'写错库/不进事务'的成因");
            Throwable root = rootCause(context.getStartupFailure());
            assertInstanceOf(IllegalStateException.class, root,
                    () -> "必须是组件的事务模板校验失败: " + failureText(context.getStartupFailure()));
            assertTrue(root.getMessage().contains("masterTransactionManager"),
                    "报错必须点名主库事务管理器: " + root.getMessage());
            assertTrue(root.getMessage().contains("otherDbTxManager"), "报错必须列出候选: " + root.getMessage());
            assertTrue(root.getMessage().contains("spring.datasource.url"), "报错必须给出出路: " + root.getMessage());
            assertTrue(failureText(context.getStartupFailure()).contains("IdempotencyAutoConfiguration"),
                    "报错必须指向本组件的装配点: " + failureText(context.getStartupFailure()));
        });
    }

    /** 没有模板可用（宿主排除事务自动配置等）：组件不自建模板，启动失败并给出出路 */
    @Test
    void missingTemplateFailsFastWithGuidance() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.enabled=true", "spring.application.name=idem-tx-wiring")
                .withBean(StringRedisTemplate.class, IdempotencyTransactionTemplateWiringTest::stubRedisTemplate)
                .withBean(RedissonClient.class, IdempotencyTransactionTemplateWiringTest::stubRedisson)
                .withBean(MASTER_FACTORY, SqlSessionFactory.class, IdempotencyTransactionTemplateWiringTest::stubSqlSessionFactory)
                .withUserConfiguration(MasterManagerCase.class)
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "容器里没有 TransactionTemplate 时必须启动失败：组件不注册事务基础设施");
                    Throwable root = rootCause(context.getStartupFailure());
                    assertInstanceOf(IllegalStateException.class, root,
                            () -> "必须是组件的事务模板校验失败: " + failureText(context.getStartupFailure()));
                    assertTrue(root.getMessage().contains("自行声明"),
                            "报错必须给出出路: " + root.getMessage());
                    assertTrue(root.getMessage().contains("TransactionTemplate"),
                            "报错必须说清缺的是什么: " + root.getMessage());
                });
    }

    /** 幂等装配最小上下文：依赖全用桩（不连 DB/Redis），主库设施按 ds 的固定名字注入 */
    private static ApplicationContextRunner runner(Class<?>... userConfigurations) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        IdempotencyAutoConfiguration.class, TransactionAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.enabled=true", "spring.application.name=idem-tx-wiring")
                .withBean(StringRedisTemplate.class, IdempotencyTransactionTemplateWiringTest::stubRedisTemplate)
                .withBean(RedissonClient.class, IdempotencyTransactionTemplateWiringTest::stubRedisson)
                .withBean(MASTER_DATASOURCE, DataSource.class, ProbeDataSource::allTablesExist)
                .withBean(MASTER_FACTORY, SqlSessionFactory.class, IdempotencyTransactionTemplateWiringTest::stubSqlSessionFactory)
                .withUserConfiguration(userConfigurations);
    }

    /** 主库设施的名字即契约（component-ds 装配的那套） */
    private static final String MASTER_DATASOURCE = "masterDataSource";
    private static final String MASTER_FACTORY = "masterSqlSessionFactory";

    /** 从 IdempotencyGuard 上取实际注入的事务模板 */
    private static Object guardTemplate(org.springframework.context.ApplicationContext context) {
        return ReflectionTestUtils.getField(context.getBean(IdempotencyGuard.class), "transactionTemplate");
    }

    /** 主库事务管理器（单库宿主；多库宿主的业务库也用它） */
    @Configuration(proxyBeanMethods = false)
    static class MasterManagerCase {

        @Bean(MASTER_MANAGER)
        @Primary
        PlatformTransactionManager masterTransactionManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }
    }

    /** 附加库事务管理器：有自己的名字，不标 @Primary */
    @Configuration(proxyBeanMethods = false)
    static class ReportManagerCase {

        @Bean("reportTransactionManager")
        PlatformTransactionManager reportTransactionManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }
    }

    /** 附加库模板：绑附加库事务管理器（宿主为别的库声明的模板会让 Boot 标准模板退让） */
    @Configuration(proxyBeanMethods = false)
    static class ReportTemplateCase {

        @Bean("reportTransactionTemplate")
        TransactionTemplate reportTransactionTemplate(
                @org.springframework.beans.factory.annotation.Qualifier("reportTransactionManager")
                PlatformTransactionManager reportTransactionManager) {
            return new TransactionTemplate(reportTransactionManager);
        }
    }

    /** 主库与附加库各一个模板，都不标 @Primary：按绑定关系取主库那份 */
    @Configuration(proxyBeanMethods = false)
    static class MasterAndReportTemplatesCase {

        @Bean("masterLibraryTemplate")
        TransactionTemplate masterLibraryTemplate(
                @org.springframework.beans.factory.annotation.Qualifier(MASTER_MANAGER)
                PlatformTransactionManager masterTransactionManager) {
            return new TransactionTemplate(masterTransactionManager);
        }

        @Bean("reportLibraryTemplate")
        TransactionTemplate reportLibraryTemplate(
                @org.springframework.beans.factory.annotation.Qualifier("reportTransactionManager")
                PlatformTransactionManager reportTransactionManager) {
            return new TransactionTemplate(reportTransactionManager);
        }
    }

    /** 宿主自定义模板：语义与 Boot 默认不同（REQUIRES_NEW），用于验证"原样复用" */
    @Configuration(proxyBeanMethods = false)
    static class HostTemplateCase {

        @Bean
        TransactionTemplate hostTransactionTemplate(
                @org.springframework.beans.factory.annotation.Qualifier(MASTER_MANAGER)
                PlatformTransactionManager masterTransactionManager) {
            TransactionTemplate template = new TransactionTemplate(masterTransactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return template;
        }
    }

    /** 同一主库 TM 上多个模板都无 @Primary：歧义必须变成启动失败 */
    @Configuration(proxyBeanMethods = false)
    static class AmbiguousTemplateCase {

        @Bean
        TransactionTemplate hostTxA(@org.springframework.beans.factory.annotation.Qualifier(MASTER_MANAGER)
                                    PlatformTransactionManager masterTransactionManager) {
            return new TransactionTemplate(masterTransactionManager);
        }

        @Bean
        TransactionTemplate hostTxB(@org.springframework.beans.factory.annotation.Qualifier(MASTER_MANAGER)
                                    PlatformTransactionManager masterTransactionManager) {
            return new TransactionTemplate(masterTransactionManager);
        }
    }

    /** 多个模板、其中一个 @Primary：取 @Primary */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryTemplateCase {

        @Bean
        @Primary
        TransactionTemplate hostPrimaryTemplate(
                @org.springframework.beans.factory.annotation.Qualifier(MASTER_MANAGER)
                PlatformTransactionManager masterTransactionManager) {
            return new TransactionTemplate(masterTransactionManager);
        }

        @Bean
        TransactionTemplate hostOtherTemplate(
                @org.springframework.beans.factory.annotation.Qualifier(MASTER_MANAGER)
                PlatformTransactionManager masterTransactionManager) {
            return new TransactionTemplate(masterTransactionManager);
        }
    }

    /** 宿主只声明了别的名字的事务管理器（没有主库那套）：失败并点名候选 */
    @Configuration(proxyBeanMethods = false)
    static class NoMasterManagerCase {

        @Bean
        PlatformTransactionManager otherDbTxManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }
    }

    /** 主库事务管理器的 Bean 名（component-ds 装配的那套） */
    private static final String MASTER_MANAGER = "masterTransactionManager";

    /** 数据源桩：不连任何库（守卫与模板只做绑定，不会真正开事务） */
    private static DataSource stubDataSource() {
        return (DataSource) Proxy.newProxyInstance(IdempotencyTransactionTemplateWiringTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> null);
    }

    /** SqlSessionFactory 桩：MapperFactoryBean 只需要能建 SqlSessionTemplate 的真实工厂，不触库 */
    private static SqlSessionFactory stubSqlSessionFactory() {
        Environment environment = new Environment("idem-tx-wiring", new ManagedTransactionFactory(), stubDataSource());
        return new SqlSessionFactoryBuilder().build(new org.apache.ibatis.session.Configuration(environment));
    }

    /** RedissonClient 桩：启动期只检查存在性 */
    private static RedissonClient stubRedisson() {
        return (RedissonClient) Proxy.newProxyInstance(IdempotencyTransactionTemplateWiringTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> null);
    }

    /** StringRedisTemplate 桩：启动期只检查存在性 */
    private static StringRedisTemplate stubRedisTemplate() {
        RedisConnectionFactory connectionFactory = (RedisConnectionFactory) Proxy.newProxyInstance(
                IdempotencyTransactionTemplateWiringTest.class.getClassLoader(),
                new Class<?>[]{RedisConnectionFactory.class}, (proxy, method, args) -> null);
        return new StringRedisTemplate(connectionFactory);
    }

    /** 取最深层原因 */
    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** 拼接启动失败的完整文案（含 cause 链），用于断言报错指向本组件 */
    private static String failureText(Throwable failure) {
        if (failure == null) {
            return "<null>";
        }
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage()).append(" | ");
        }
        return text.toString();
    }

}
