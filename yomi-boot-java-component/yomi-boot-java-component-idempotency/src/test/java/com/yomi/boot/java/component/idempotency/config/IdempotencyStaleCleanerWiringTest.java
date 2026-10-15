package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.idempotency.stale.IdempotencyStaleCleaner;
import com.yomi.boot.java.component.roundrobin.config.RoundRobinAutoConfiguration;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinRegistry;
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
 * 记录清理任务与 roundrobin 的装配契约：开启清理却没启用 roundrobin 时启动失败（fail-fast），
 * 不再出现"启动日志说已启用、任务根本没注册"（记录表只增不删）的静默失效。
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
        // 注解条件只认 true/TRUE（Boot 4.1.1 实测：1/yes/on 不匹配）→ 属性写成 1 时 roundrobin 自身也不装配。
        // 守卫判断的是"注册表 Bean 是否存在"，因此同样认定为不可用，而不是静默启动（引入真实 autoconfig 验证）
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
                .withBean(RoundRobinRegistry.class, RoundRobinRegistry::new)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> failureText(context.getStartupFailure()));
                    assertNotNull(context.getBean(IdempotencyStaleCleaner.class), "清理器 Bean 应装配");
                    RoundRobinRegistry registry = context.getBean(RoundRobinRegistry.class);
                    assertTrue(registry.contains(IdempotencyStaleCleaner.TASK_NAME),
                            "两项都开启时清理任务应真正注册到 roundrobin");
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
