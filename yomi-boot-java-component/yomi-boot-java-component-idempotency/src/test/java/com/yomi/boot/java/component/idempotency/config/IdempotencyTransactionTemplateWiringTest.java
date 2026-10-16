package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.idempotency.core.IdempotencyGuard;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
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
 * 事务模板解析契约（IPC I4 复核）：容器里有 TransactionTemplate（Boot 标准 Bean / 宿主自定义）时组件原样复用
 * 且不再注册任何模板 Bean；没有模板可复用时按事务管理器自建私有默认 REQUIRED 模板（不注册、不抢注）；
 * 多候选模板或管理器无 @Primary 时启动失败并点名本组件与候选（与 Boot 同口径，不静默挑一个写错库）。
 */
class IdempotencyTransactionTemplateWiringTest {

    /** 默认应用：守卫复用容器里的 Boot 标准 transactionTemplate，容器里不出现组件注册的模板 Bean */
    @Test
    void defaultAppReusesBootStandardTemplate() {
        runner(SingleManagerCase.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
            assertArrayEquals(new String[]{"transactionTemplate"},
                    context.getBeanNamesForType(TransactionTemplate.class),
                    "默认应用里只能有 Boot 标准模板；组件必须复用而不是另行注册");
            TransactionTemplate template = context.getBean(TransactionTemplate.class);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, template.getPropagationBehavior());
            assertSame(template, guardTemplate(context), "守卫必须用容器里的模板（占位、业务与写回同一事务）");
            assertSame(context.getBean("transactionManager"), template.getTransactionManager());
        });
    }

    /** 宿主自定义模板（REQUIRES_NEW 等语义）：原样复用，不改写、不另建 */
    @Test
    void hostTemplateIsReusedAsIs() {
        runner(SingleManagerCase.class, HostTemplateCase.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
            assertArrayEquals(new String[]{"hostTransactionTemplate"},
                    context.getBeanNamesForType(TransactionTemplate.class),
                    "宿主模板存在时组件不得再注册自己的模板");
            TransactionTemplate host = context.getBean("hostTransactionTemplate", TransactionTemplate.class);
            assertSame(host, guardTemplate(context), "宿主模板语义必须原样带到守卫");
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, host.getPropagationBehavior());
        });
    }

    /** 多事务管理器 + @Primary（多数据源宿主的正确形态）：与 Boot 同口径，用 @Primary 那个 */
    @Test
    void multipleManagersBindToPrimary() {
        runner(PrimaryManagerCase.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
            assertSame(context.getBean("primaryTxManager"),
                    context.getBean(TransactionTemplate.class).getTransactionManager(),
                    "多事务管理器时以 @Primary 为唯一解（宿主应把 @Primary 放在业务库管理器上）");
            assertSame(context.getBean(TransactionTemplate.class), guardTemplate(context));
        });
    }

    /** 多候选模板且无 @Primary：启动失败点名 idempotencyGuard 与候选 Bean 名，不允许静默挑一个 */
    @Test
    void ambiguousTemplatesFailFastInsteadOfPickingOne() {
        runner(SingleManagerCase.class, AmbiguousTemplateCase.class).run(context -> {
            assertNotNull(context.getStartupFailure(),
                    "多候选模板无 @Primary 必须启动失败：静默挑一个可能丢掉宿主为守卫准备的传播语义");
            Throwable root = rootCause(context.getStartupFailure());
            assertInstanceOf(NoUniqueBeanDefinitionException.class, root,
                    () -> "必须因事务模板歧义失败: " + failureText(context.getStartupFailure()));
            assertTrue(root.getMessage().contains("hostTxA") && root.getMessage().contains("hostTxB"),
                    "报错必须点名候选: " + root.getMessage());
            assertTrue(failureText(context.getStartupFailure()).contains("idempotencyGuard"),
                    "报错链必须指向本组件的装配点（I4 复核断言）: " + failureText(context.getStartupFailure()));
        });
    }

    /** 多事务管理器且无 @Primary：启动失败点名候选，不允许静默挑一个（可能写错库/不进业务事务） */
    @Test
    void ambiguousManagersFailFastInsteadOfPickingOne() {
        runner(AmbiguousManagerCase.class).run(context -> {
            assertNotNull(context.getStartupFailure(),
                    "多事务管理器无 @Primary 必须启动失败：静默挑一个正是'写错库/不进事务'的成因");
            Throwable root = rootCause(context.getStartupFailure());
            assertInstanceOf(NoUniqueBeanDefinitionException.class, root,
                    () -> "必须因事务管理器歧义失败: " + failureText(context.getStartupFailure()));
            assertTrue(root.getMessage().contains("businessDbTxManager")
                            && root.getMessage().contains("otherDbTxManager"),
                    "报错必须点名候选: " + root.getMessage());
            assertTrue(failureText(context.getStartupFailure()).contains("idempotencyGuard"),
                    "报错链必须指向本组件的装配点: " + failureText(context.getStartupFailure()));
        });
    }

    /** 无模板可复用（宿主排除 Boot 事务自动配置）：组件按事务管理器自建私有模板，不注册为 Bean（不抢注） */
    @Test
    void noTemplateAvailableFallsBackToPrivateTemplate() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.enabled=true", "spring.application.name=idem-tx-wiring")
                .withBean(StringRedisTemplate.class, IdempotencyTransactionTemplateWiringTest::stubRedisTemplate)
                .withBean(RedissonClient.class, IdempotencyTransactionTemplateWiringTest::stubRedisson)
                .withBean(SqlSessionFactory.class, IdempotencyTransactionTemplateWiringTest::stubSqlSessionFactory)
                .withUserConfiguration(SingleManagerCase.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
                    assertEquals(0, context.getBeanNamesForType(TransactionTemplate.class).length,
                            "兜底模板必须私有：注册进容器会占用/挤掉 Boot 标准 transactionTemplate Bean 名");
                    TransactionTemplate fallback = (TransactionTemplate) guardTemplate(context);
                    assertNotNull(fallback, "无模板可复用时守卫必须有兜底模板");
                    assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, fallback.getPropagationBehavior());
                    assertSame(context.getBean("transactionManager"), fallback.getTransactionManager(),
                            "兜底模板必须绑定应用的事务管理器");
                });
    }

    /** 幂等装配最小上下文：依赖全用桩（不连 DB/Redis），事务管理器由用例注入；组件先于 Boot 声明以复现生产装配序 */
    private static ApplicationContextRunner runner(Class<?>... userConfigurations) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        IdempotencyAutoConfiguration.class, TransactionAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.enabled=true", "spring.application.name=idem-tx-wiring")
                .withBean(StringRedisTemplate.class, IdempotencyTransactionTemplateWiringTest::stubRedisTemplate)
                .withBean(RedissonClient.class, IdempotencyTransactionTemplateWiringTest::stubRedisson)
                .withBean(SqlSessionFactory.class, IdempotencyTransactionTemplateWiringTest::stubSqlSessionFactory)
                .withUserConfiguration(userConfigurations);
    }

    /** 从 IdempotencyGuard 上取实际注入的事务模板 */
    private static Object guardTemplate(org.springframework.context.ApplicationContext context) {
        return ReflectionTestUtils.getField(context.getBean(IdempotencyGuard.class), "transactionTemplate");
    }

    /** 单事务管理器（单库宿主） */
    @Configuration(proxyBeanMethods = false)
    static class SingleManagerCase {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }
    }

    /** 宿主自定义模板：语义与 Boot 默认不同（REQUIRES_NEW），用于验证"原样复用" */
    @Configuration(proxyBeanMethods = false)
    static class HostTemplateCase {

        @Bean
        TransactionTemplate hostTransactionTemplate(PlatformTransactionManager transactionManager) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return template;
        }
    }

    /** 多个模板都无 @Primary：歧义必须变成启动失败 */
    @Configuration(proxyBeanMethods = false)
    static class AmbiguousTemplateCase {

        @Bean
        TransactionTemplate hostTxA(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        TransactionTemplate hostTxB(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }

    /** 多库宿主：业务库管理器 @Primary，另有一个次库管理器 */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryManagerCase {

        @Bean
        @Primary
        PlatformTransactionManager primaryTxManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }

        @Bean
        PlatformTransactionManager otherTxManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }
    }

    /** 多库宿主但没标 @Primary：歧义必须变成启动失败 */
    @Configuration(proxyBeanMethods = false)
    static class AmbiguousManagerCase {

        @Bean
        PlatformTransactionManager businessDbTxManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }

        /** 次库通常由宿主自持连接池、不注册 DataSource Bean */
        @Bean
        PlatformTransactionManager otherDbTxManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }
    }

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
