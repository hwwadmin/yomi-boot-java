package com.yomi.boot.java.component.event.config;

import com.yomi.boot.java.component.event.consume.EventInboxGuard;
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
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事务模板解析契约：容器里已有 TransactionTemplate（Boot 标准 Bean / 宿主自定义）时组件原样复用，
 * 组件只在没有可复用模板时注册默认 REQUIRED 模板；
 * 多事务管理器无 @Primary 时启动失败并点名候选，不自行挑选
 */
class EventTransactionTemplateWiringTest {

    /** 默认应用：容器里的 TransactionTemplate 是 Boot 标准 Bean（组件只复用，不注册自己的模板） */
    @Test
    void defaultAppKeepsBootStandardTemplate() {
        DataSource dataSource = stubDataSource();
        consumerRunner(dataSource).withUserConfiguration(SingleManagerCase.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
            assertArrayEquals(new String[]{"transactionTemplate"},
                    context.getBeanNamesForType(TransactionTemplate.class),
                    "默认应用里不能出现组件自注册的模板；组件必须让 Boot 的标准 transactionTemplate 先注册");
            TransactionTemplate template = context.getBean(TransactionTemplate.class);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, template.getPropagationBehavior(),
                    "复用的 Boot 模板是默认 REQUIRED");
            assertSame(context.getBean("transactionManager"), template.getTransactionManager(),
                    "模板必须绑定应用的事务管理器");
            assertSame(template, guardTemplate(context),
                    "EventInboxGuard 必须用容器里的模板（inbox 插入与业务写入同事务）");
        });
    }

    /** 宿主自定义模板（REQUIRES_NEW 等语义）：组件原样复用宿主语义 */
    @Test
    void hostTemplateIsReusedAsIs() {
        consumerRunner(stubDataSource())
                .withUserConfiguration(SingleManagerCase.class, HostTemplateCase.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    assertArrayEquals(new String[]{"hostTransactionTemplate"},
                            context.getBeanNamesForType(TransactionTemplate.class),
                            "宿主模板存在时组件不得再注册自己的模板");
                    TransactionTemplate host = context.getBean("hostTransactionTemplate", TransactionTemplate.class);
                    assertSame(host, guardTemplate(context), "宿主模板语义必须原样带到组件");
                    assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, host.getPropagationBehavior());
                });
    }

    /** 多事务管理器 + @Primary（多数据源宿主的正确形态）：组件与 Boot 同口径，使用 @Primary 管理器 */
    @Test
    void multipleManagersBindToPrimary() {
        consumerRunner(stubDataSource()).withUserConfiguration(PrimaryManagerCase.class).run(context -> {
            assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
            assertSame(context.getBean("primaryTxManager"), context.getBean(TransactionTemplate.class).getTransactionManager(),
                    "多事务管理器时以 @Primary 为唯一解（宿主应把 @Primary 放在业务库管理器上）");
            assertSame(context.getBean(TransactionTemplate.class), guardTemplate(context));
        });
    }

    /** 多事务管理器无 @Primary：启动失败并点名候选（自行挑选可能写错库或偏离业务事务） */
    @Test
    void ambiguousManagersFailFastInsteadOfPickingOne() {
        consumerRunner(stubDataSource()).withUserConfiguration(AmbiguousManagerCase.class).run(context -> {
            assertNotNull(context.getStartupFailure(),
                    "多事务管理器无 @Primary 必须启动失败：静默挑一个正是'写错库/不进事务'的成因");
            Throwable root = rootCause(context.getStartupFailure());
            assertInstanceOf(NoUniqueBeanDefinitionException.class, root,
                    () -> "必须因事务管理器歧义失败: " + context.getStartupFailure());
            assertTrue(root.getMessage().contains("businessDbTxManager")
                            && root.getMessage().contains("otherDbTxManager"),
                    "报错必须点名候选: " + root.getMessage());
        });
    }

    /** 没有 Boot 事务自动配置（宿主排除/无模板可复用）：组件注册默认 REQUIRED 模板，保证自身可用 */
    @Test
    void componentRegistersFallbackWhenNoTemplateAvailable() {
        DataSource dataSource = stubDataSource();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EventAutoConfiguration.class))
                .withPropertyValues("yomi.event.consumer-enabled=true", "yomi.event.service-name=tx-wiring-test")
                .withBean("dataSource", DataSource.class, () -> dataSource)
                .withBean("sqlSessionFactory", SqlSessionFactory.class, () -> stubSqlSessionFactory(dataSource))
                .withBean("stringRedisTemplate", StringRedisTemplate.class, EventTransactionTemplateWiringTest::stubRedisTemplate)
                .withBean("redissonClient", RedissonClient.class, EventTransactionTemplateWiringTest::stubRedisson)
                .withUserConfiguration(SingleManagerCase.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    assertArrayEquals(new String[]{"eventTransactionTemplate"},
                            context.getBeanNamesForType(TransactionTemplate.class),
                            "无模板可复用时由组件兜底");
                    assertEquals(TransactionDefinition.PROPAGATION_REQUIRED,
                            context.getBean(TransactionTemplate.class).getPropagationBehavior());
                });
    }

    /** 消费侧最小上下文：组件依赖全用桩（不连 DB/Redis/Kafka），事务管理器由用例注入 */
    private static ApplicationContextRunner consumerRunner(DataSource dataSource) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class, EventAutoConfiguration.class))
                .withPropertyValues("yomi.event.consumer-enabled=true", "yomi.event.service-name=tx-wiring-test")
                .withBean("dataSource", DataSource.class, () -> dataSource)
                .withBean("sqlSessionFactory", SqlSessionFactory.class, () -> stubSqlSessionFactory(dataSource))
                .withBean("stringRedisTemplate", StringRedisTemplate.class, EventTransactionTemplateWiringTest::stubRedisTemplate)
                .withBean("redissonClient", RedissonClient.class, EventTransactionTemplateWiringTest::stubRedisson);
    }

    /** 从 EventInboxGuard 上取实际注入的事务模板 */
    private static Object guardTemplate(org.springframework.context.ApplicationContext context) {
        return ReflectionTestUtils.getField(context.getBean(EventInboxGuard.class), "transactionTemplate");
    }

    /** 单事务管理器（单库宿主） */
    @Configuration(proxyBeanMethods = false)
    static class SingleManagerCase {

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
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

    /** 多库宿主：业务库管理器 @Primary，另有一个次库管理器 */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryManagerCase {

        @Bean
        @Primary
        PlatformTransactionManager primaryTxManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PlatformTransactionManager otherTxManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }
    }

    /** 多库宿主但没标 @Primary：歧义按启动失败处理 */
    @Configuration(proxyBeanMethods = false)
    static class AmbiguousManagerCase {

        @Bean
        PlatformTransactionManager businessDbTxManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        /** 次库通常由宿主自持连接池、不注册 DataSource Bean */
        @Bean
        PlatformTransactionManager otherDbTxManager() {
            return new DataSourceTransactionManager(stubDataSource());
        }
    }

    /** 数据源桩："所有表都存在"，不连任何库 */
    private static DataSource stubDataSource() {
        return (DataSource) Proxy.newProxyInstance(EventTransactionTemplateWiringTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return null;
                    }
                    return Proxy.newProxyInstance(EventTransactionTemplateWiringTest.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (connection, connectionMethod, connectionArgs) -> {
                                if (!"createStatement".equals(connectionMethod.getName())) {
                                    return null;
                                }
                                return Proxy.newProxyInstance(EventTransactionTemplateWiringTest.class.getClassLoader(),
                                        new Class<?>[]{Statement.class}, (statement, statementMethod, statementArgs) -> null);
                            });
                });
    }

    /** SqlSessionFactory 桩：MapperFactoryBean 只需要能建 SqlSessionTemplate 的真实工厂，不触库 */
    private static SqlSessionFactory stubSqlSessionFactory(DataSource dataSource) {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new org.apache.ibatis.mapping.Environment("wiring-test", new ManagedTransactionFactory(), dataSource));
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** RedissonClient 桩：启动期只检查存在性 */
    private static RedissonClient stubRedisson() {
        return (RedissonClient) Proxy.newProxyInstance(EventTransactionTemplateWiringTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> null);
    }

    /** StringRedisTemplate 桩：启动期只检查存在性 */
    private static StringRedisTemplate stubRedisTemplate() {
        RedisConnectionFactory connectionFactory = (RedisConnectionFactory) Proxy.newProxyInstance(
                EventTransactionTemplateWiringTest.class.getClassLoader(),
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

}
