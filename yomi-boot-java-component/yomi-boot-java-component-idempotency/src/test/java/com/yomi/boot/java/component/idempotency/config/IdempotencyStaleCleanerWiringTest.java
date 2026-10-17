package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.idempotency.stale.IdempotencyStaleCleaner;
import com.yomi.boot.java.component.roundrobin.RoundRobinRegistry;
import com.yomi.boot.java.component.roundrobin.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.config.RoundRobinAutoConfiguration;
import com.yomi.boot.java.component.roundrobin.lock.RoundRobinLockClient;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记录清理任务与 roundrobin 的装配：开启清理却没启用 roundrobin 时启动失败；
 * 开启清理时日志说"已启用"而任务没注册（记录表只增不删）的情况在启动期就被拦住。
 * <p>依赖全为 stub，离线运行。</p>
 */
class IdempotencyStaleCleanerWiringTest {

    @Test
    void failsFastWhenStaleCleanerEnabledWithoutRoundRobin() {
        runner().withPropertyValues("yomi.idempotency.stale-cleaner-enabled=true").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "开了清理但没开 roundrobin 必须启动失败，否则清理静默不注册");
            String text = failureText(failure);
            assertTrue(text.contains("yomi.roundrobin.enabled"), "报错要指出需要 roundrobin: " + text);
            assertTrue(text.contains("stale-cleaner-enabled=false"), "报错要给出关闭清理的出路: " + text);
        });
    }

    @Test
    void startsWhenStaleCleanerDisabled() {
        runner().withPropertyValues("yomi.idempotency.stale-cleaner-enabled=false").run(context ->
                assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure())));
    }

    @Test
    void failsFastWhenRoundRobinFlagIsTruthyButNotTrue() {
        // 注解条件只认 true/TRUE：属性写成 1/yes/on 时 roundrobin 自身也不装配。
        // 这里用真实 autoconfig 验证：守卫判断的是"注册表 Bean 是否存在"，注册表不在即认定为不可用
        runner().withConfiguration(AutoConfigurations.of(RoundRobinAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.stale-cleaner-enabled=true", "yomi.roundrobin.enabled=1")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "roundrobin 未真正装配时清理任务注册不进去，必须启动失败");
                    assertTrue(failureText(failure).contains("yomi.roundrobin.enabled"), failureText(failure));
                });
    }

    @Test
    void registersCleanerTaskWhenBothEnabled() {
        runner().withPropertyValues("yomi.idempotency.stale-cleaner-enabled=true", "yomi.roundrobin.enabled=true")
                .withBean(RoundRobinRegistry.class, IdempotencyStaleCleanerWiringTest::idleRegistry)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
                    assertNotNull(context.getBean(IdempotencyStaleCleaner.class), "清理器 Bean 应装配");
                    RoundRobinRegistry registry = context.getBean(RoundRobinRegistry.class);
                    assertTrue(registry.contains(IdempotencyStaleCleaner.TASK_NAME),
                            "两项都开启时清理任务应真正注册到 roundrobin");
                });
    }

    /** 锁客户端桩：频率锁始终获取不到 → 注册表随容器启停，但实例只休眠、不触达 Redis */
    private static RoundRobinRegistry idleRegistry() {
        return new RoundRobinRegistry(new RoundRobinLockClient(null, null, null) {
            @Override
            public boolean tryAcquireFrequency(RoundRobinTask task) {
                return false;
            }
        });
    }

    /** 构造带测试 Bean 的 ApplicationContextRunner（与建表检查测试同款桩；Mapper 由装配类自己的 @MapperScan 扫出） */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.enabled=true", "spring.application.name=idem-stale-wiring")
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(proxy(RedisConnectionFactory.class)))
                .withBean(RedissonClient.class, () -> proxy(RedissonClient.class))
                .withBean(SqlSessionFactory.class, IdempotencyStaleCleanerWiringTest::sqlSessionFactoryStub)
                .withBean(PlatformTransactionManager.class, () -> proxy(PlatformTransactionManager.class));
    }

    /** 让装配类扫出的 Mapper 能实例化：真实 Configuration + 空 DataSource（不触库） */
    private static SqlSessionFactory sqlSessionFactoryStub() {
        Environment environment = new Environment("idem-stale-wiring", new ManagedTransactionFactory(),
                proxy(DataSource.class));
        return new SqlSessionFactoryBuilder().build(new Configuration(environment));
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
