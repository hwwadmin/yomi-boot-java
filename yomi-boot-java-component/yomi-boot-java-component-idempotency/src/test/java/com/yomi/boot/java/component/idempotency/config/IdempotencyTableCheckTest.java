package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.ds.support.TableExistingChecker;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验证启动期建表检查：表在 → 正常启动；缺表 → 启动失败（fail-fast），报错点名表与建表 SQL。
 * <p>依赖全部为 stub，离线运行；只有 DataSource 的建表 SQL 行为由 {@code tableExists} 控制。
 */
class IdempotencyTableCheckTest {

    @Test
    void startsWhenTableExists() {
        runner(true).run(context -> {
            assertNull(context.getStartupFailure(), () -> "表存在时不应启动失败: " + failureText(context.getStartupFailure()));
            assertNotNull(context.getBean(TableExistingChecker.class), "检查器 Bean 应已装配");
        });
    }

    @Test
    void failsFastWhenTableMissing() {
        runner(false).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "缺表时必须让启动失败（fail-fast）");
            String text = failureText(failure);
            assertTrue(text.contains("sys_idempotency_record"), "报错必须点名缺失的表: " + text);
            assertTrue(text.contains("sql/sys_idempotency_record.sql"), "报错必须给出建表 SQL 路径: " + text);
        });
    }

    /** 构造带测试 Bean 的 ApplicationContextRunner（Mapper 由装配类自己的 @MapperScan 扫出） */
    private ApplicationContextRunner runner(boolean tableExists) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.enabled=true", "spring.application.name=idem-table-check")
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(redisConnectionFactoryStub()))
                .withBean(RedissonClient.class, () -> proxy(RedissonClient.class))
                .withBean(SqlSessionFactory.class, IdempotencyTableCheckTest::sqlSessionFactoryStub)
                .withBean(PlatformTransactionManager.class, () -> proxy(PlatformTransactionManager.class))
                .withBean(DataSource.class, () -> dataSourceStub(tableExists));
    }

    /** 让装配类扫出的 Mapper 能实例化：真实 Configuration + 空 DataSource（不触库） */
    private static SqlSessionFactory sqlSessionFactoryStub() {
        Environment environment = new Environment("idem-table-check", new ManagedTransactionFactory(),
                proxy(DataSource.class));
        return new SqlSessionFactoryBuilder().build(new Configuration(environment));
    }

    /** 构造 RedisConnectionFactory 桩 */
    private static RedisConnectionFactory redisConnectionFactoryStub() {
        return proxy(RedisConnectionFactory.class);
    }

    /** 建表 SQL 行为由 existing 控制：false 时 executeQuery 抛"表不存在" */
    private static DataSource dataSourceStub(boolean existing) {
        return (DataSource) Proxy.newProxyInstance(IdempotencyTableCheckTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return defaultReturn(proxy, method, args);
                    }
                    return Proxy.newProxyInstance(IdempotencyTableCheckTest.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (conn, connMethod, connArgs) -> {
                                if (!"createStatement".equals(connMethod.getName())) {
                                    return defaultReturn(conn, connMethod, connArgs);
                                }
                                return (Statement) Proxy.newProxyInstance(
                                        IdempotencyTableCheckTest.class.getClassLoader(),
                                        new Class<?>[]{Statement.class}, (stmt, stmtMethod, stmtArgs) -> {
                                            if (!"executeQuery".equals(stmtMethod.getName())) {
                                                return defaultReturn(stmt, stmtMethod, stmtArgs);
                                            }
                                            if (!existing) {
                                                throw new SQLException(
                                                        "Table 'sys_idempotency_record' doesn't exist");
                                            }
                                            return null;
                                        });
                            });
                });
    }

    /** 从 ApplicationContext 取 Bean 的 ObjectProvider */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(IdempotencyTableCheckTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> defaultReturn(proxy, method, args));
    }

    /** 未知方法返回默认值（close 等生命周期方法为无操作），仅 Object 方法给出合理实现 */
    private static Object defaultReturn(Object proxy, java.lang.reflect.Method method, Object[] args) {
        switch (method.getName()) {
            case "toString" -> {
                return "stub";
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            }
            default -> {
                return null;
            }
        }
    }

    /** 拼接表检查失败文案 */
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
