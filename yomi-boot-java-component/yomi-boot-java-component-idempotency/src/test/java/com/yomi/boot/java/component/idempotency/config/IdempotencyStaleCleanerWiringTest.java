package com.yomi.boot.java.component.idempotency.config;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.yomi.boot.java.component.ds.support.DsDatabaseIdProvider;
import com.yomi.boot.java.component.idempotency.IdempotencyDataSources;
import com.yomi.boot.java.component.idempotency.repository.IIdempotencyRecordRepository;
import com.yomi.boot.java.component.idempotency.repository.mapper.IdempotencyRecordMapper;
import com.yomi.boot.java.component.idempotency.stale.IdempotencyStaleCleaner;
import com.yomi.boot.java.component.redis.schedule.ClusterTask;
import com.yomi.boot.java.core.utils.SpringUtils;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记录清理的装配：开启清理即装配清理器与集群定时任务（任务名即锁键、间隔取 scan-interval），
 * 关闭清理则两者都不装配
 * <p>依赖全为 stub，离线运行。</p>
 */
class IdempotencyStaleCleanerWiringTest {

    @Test
    void wiresCleanerAndClusterTaskWhenEnabled() {
        runner().withPropertyValues("yomi.idempotency.stale-cleaner-enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
                    assertNotNull(context.getBean(IdempotencyStaleCleaner.class), "清理器 Bean 应装配");
                    ClusterTask task = context.getBean(ClusterTask.class);
                    assertEquals(IdempotencyStaleCleaner.TASK_NAME, task.getTaskName());
                    assertEquals("yomi:schedule:idem-stale-wiring:lock:" + IdempotencyStaleCleaner.TASK_NAME,
                            task.getLockKey());
                    assertEquals(Duration.ofMinutes(10), task.getInterval(), "间隔默认取 scan-interval");
                });
    }

    @Test
    void wiresNothingWhenDisabled() {
        runner().withPropertyValues("yomi.idempotency.stale-cleaner-enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
                    assertTrue(context.getBeansOfType(IdempotencyStaleCleaner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ClusterTask.class).isEmpty());
                });
    }

    /** 业务附加库注册：注册即同样获得自动清理（每库一个任务，任务名带注册名），记录表 Mapper 与方言语句注入该库工厂 */
    @Test
    void registeredBusinessLibraryGetsItsOwnCleanerTask() {
        runner(BusinessReportLibrary.class).withPropertyValues("yomi.idempotency.stale-cleaner-enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));

                    IdempotencyDataSources registry = context.getBean(IdempotencyDataSources.class);
                    assertEquals(Set.of(IdempotencyDataSources.MASTER, BusinessReportLibrary.NAME),
                            context.getBean(IdempotencyDataSources.class).registrations().stream()
                                    .map(IdempotencyDataSources.Registration::name)
                                    .collect(Collectors.toSet()),
                            "默认注册主库，业务注册的附加库也要在册");
                    assertNotNull(registry.resolve(context.getBean("reportDataSource", DataSource.class)),
                            "附加库必须能按数据源身份解析到注册项");

                    Set<String> taskNames = context.getBeansOfType(ClusterTask.class).values().stream()
                            .map(ClusterTask::getTaskName).collect(Collectors.toSet());
                    assertEquals(Set.of(IdempotencyStaleCleaner.TASK_NAME,
                                    IdempotencyStaleCleaner.TASK_NAME + "-" + BusinessReportLibrary.NAME), taskNames,
                            "每个注册库一个清理任务（任务名即锁键，主库沿用原名）");

                    SqlSessionFactory reportFactory = context.getBean("reportSqlSessionFactory", SqlSessionFactory.class);
                    assertTrue(reportFactory.getConfiguration().hasMapper(IdempotencyRecordMapper.class),
                            "注册时记录表 Mapper 必须注入该库工厂");
                    assertTrue(reportFactory.getConfiguration().hasStatement(
                                    IdempotencyRecordMapper.class.getName() + ".deleteExpired"),
                            "注册时组件方言语句（限额删除）必须注入该库工厂");
                });
    }

    /** 装配期结束后再注册：直接失败（记录库不容运行期再变） */
    @Test
    void rejectsRegistrationAfterStartup() {
        runner().run(context -> {
            IdempotencyDataSources registry = context.getBean(IdempotencyDataSources.class);

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.register("late",
                    context.getBean("masterDataSource", DataSource.class), proxy(IIdempotencyRecordRepository.class)));

            assertTrue(ex.getMessage().contains("冻结"), "报错要说清注册表已冻结: " + ex.getMessage());
        });
    }

    /** 主库四件套（与 ds 装配一致：主库那套 @Primary，组件不写 ref 的 Mapper 才能按类型落主库） */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class MasterInfra {

        @Bean("masterDataSource")
        DataSource masterDataSource() {
            return ProbeDataSource.allTablesExist();
        }

        @Bean("masterSqlSessionFactory")
        @Primary
        SqlSessionFactory masterSqlSessionFactory() {
            return sqlSessionFactoryStub();
        }

        @Bean("masterTransactionManager")
        PlatformTransactionManager masterTransactionManager() {
            return proxy(PlatformTransactionManager.class);
        }
    }

    /** 业务自建的附加库：按《组件使用说明》的多数据源模板装配（MP 工厂 + 方言解析），装配期注册进幂等记录库 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class BusinessReportLibrary {

        static final String NAME = "report";

        @Bean("reportDataSource")
        DataSource reportDataSource() {
            return ProbeDataSource.allTablesExist();
        }

        @Bean("reportSqlSessionFactory")
        SqlSessionFactory reportSqlSessionFactory(@Qualifier("reportDataSource") DataSource reportDataSource,
                                                  IdempotencyDataSources registry) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(reportDataSource);
            factory.setDatabaseIdProvider(new DsDatabaseIdProvider());
            SqlSessionFactory sqlSessionFactory = factory.getObject();
            registry.register(NAME, sqlSessionFactory);
            return sqlSessionFactory;
        }
    }

    /**
     * 构造带测试 Bean 的 ApplicationContextRunner（与建表检查测试同款桩；Mapper 由装配类自己的 @MapperScan 扫出）；
     * 主库设施按 ds 的固定名字注入，事务边界由调用方给（组件不再自建/注入事务模板）
     */
    private ApplicationContextRunner runner(Class<?>... userConfigurations) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class,
                        IdempotencyAutoConfiguration.class, LifecycleAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.enabled=true", "spring.application.name=idem-stale-wiring")
                .withBean(SpringUtils.class)
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(proxy(RedisConnectionFactory.class)))
                .withBean(RedissonClient.class, IdempotencyStaleCleanerWiringTest::busyRedissonStub)
                .withUserConfiguration(MasterInfra.class)
                .withUserConfiguration(userConfigurations);
    }

    /** 让装配类扫出的 Mapper 能实例化：真实 Configuration + 空 DataSource（不触库） */
    private static SqlSessionFactory sqlSessionFactoryStub() {
        Environment environment = new Environment("idem-stale-wiring", new ManagedTransactionFactory(),
                proxy(DataSource.class));
        return new SqlSessionFactoryBuilder().build(new Configuration(environment));
    }

    /** Redisson 桩：锁始终繁忙 → 清理任务本轮跳过（装配测试不触达真 Redis） */
    private static RedissonClient busyRedissonStub() {
        RLock lock = (RLock) Proxy.newProxyInstance(IdempotencyStaleCleanerWiringTest.class.getClassLoader(),
                new Class<?>[]{RLock.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "tryLock" -> false;
                    case "toString" -> "stub-lock";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
        return (RedissonClient) Proxy.newProxyInstance(IdempotencyStaleCleanerWiringTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLock" -> lock;
                    case "toString" -> "stub-redisson";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
    }

    /** 动态代理桩：未知方法返回默认值，仅 Object 方法给出合理实现 */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(IdempotencyStaleCleanerWiringTest.class.getClassLoader(),
                new Class<?>[]{type}, (p, method, args) -> switch (method.getName()) {
                    case "toString" -> "stub";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
    }

    /** 拼接启动失败的完整文案（含 cause 链） */
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
