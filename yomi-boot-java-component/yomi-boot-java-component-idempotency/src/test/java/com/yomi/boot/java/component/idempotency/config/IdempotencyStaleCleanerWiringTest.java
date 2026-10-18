package com.yomi.boot.java.component.idempotency.config;

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
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * 构造带测试 Bean 的 ApplicationContextRunner（与建表检查测试同款桩；Mapper 由装配类自己的 @MapperScan 扫出）；
     * 事务模板由 Boot 从唯一管理器自动注册（组件不再自建模板）
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class,
                        IdempotencyAutoConfiguration.class, LifecycleAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.enabled=true", "spring.application.name=idem-stale-wiring")
                .withBean(SpringUtils.class)
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(proxy(RedisConnectionFactory.class)))
                .withBean(RedissonClient.class, IdempotencyStaleCleanerWiringTest::busyRedissonStub)
                .withBean(SqlSessionFactory.class, IdempotencyStaleCleanerWiringTest::sqlSessionFactoryStub)
                .withBean(PlatformTransactionManager.class, () -> proxy(PlatformTransactionManager.class));
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
