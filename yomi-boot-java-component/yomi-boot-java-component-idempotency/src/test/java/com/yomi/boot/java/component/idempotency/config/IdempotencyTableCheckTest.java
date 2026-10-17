package com.yomi.boot.java.component.idempotency.config;

import com.yomi.boot.java.component.ds.support.TableInitializer;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验证启动期建表：表在 → 正常启动（只探活）；缺表 → 按当前数据库方言自动执行组件建表脚本创建；
 * 脚本没建出表 → 启动失败，报错点名表与脚本路径。
 * <p>依赖全部为 stub，离线运行；建表脚本用模块内真实 sql/mysql|pgsql 脚本（走 Spring ScriptUtils 真实解析）。
 */
class IdempotencyTableCheckTest {

    private static final String TABLE = "sys_idempotency_record";

    @Test
    void startsWhenTableExists() {
        runner("MySQL", true, true, new ArrayList<>()).run(context -> {
            assertNull(context.getStartupFailure(), () -> "表存在时不应启动失败: " + failureText(context.getStartupFailure()));
            assertNotNull(context.getBean(TableInitializer.class), "建表器 Bean 应已装配");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"MySQL", "PostgreSQL"})
    void autoCreatesMissingTableByDialect(String productName) {
        List<String> executed = new ArrayList<>();
        runner(productName, false, true, executed).run(context -> {
            assertNull(context.getStartupFailure(),
                    () -> "缺表时应自动建表而非启动失败: " + failureText(context.getStartupFailure()));
            String marker = "MySQL".equals(productName)
                    ? "CREATE TABLE IF NOT EXISTS `" + TABLE + "`"
                    : "CREATE TABLE IF NOT EXISTS " + TABLE;
            assertTrue(executed.stream().anyMatch(sql -> sql.contains(marker)),
                    "必须执行 " + productName + " 版脚本的建表语句: " + executed);
        });
    }

    @Test
    void failsFastWhenAutoCreateIneffective() {
        runner("PostgreSQL", false, false, new ArrayList<>()).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "脚本没建出表时必须让启动失败（fail-fast）");
            String text = failureText(failure);
            assertTrue(text.contains(TABLE), "报错必须点名缺失的表: " + text);
            assertTrue(text.contains("sql/pgsql/" + TABLE + ".sql"), "报错必须给出建表脚本路径: " + text);
        });
    }

    /** 构造带测试 Bean 的 ApplicationContextRunner（Mapper 由装配类自己的 @MapperScan 扫出） */
    private ApplicationContextRunner runner(String productName, boolean tableExists, boolean ddlEffective,
                                           List<String> executedSql) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .withPropertyValues("yomi.idempotency.enabled=true", "spring.application.name=idem-table-check")
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(redisConnectionFactoryStub()))
                .withBean(RedissonClient.class, () -> proxy(RedissonClient.class))
                .withBean(SqlSessionFactory.class, IdempotencyTableCheckTest::sqlSessionFactoryStub)
                .withBean(PlatformTransactionManager.class, () -> proxy(PlatformTransactionManager.class))
                .withBean(DataSource.class, () -> dataSourceStub(productName, tableExists, ddlEffective, executedSql));
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

    /**
     * 桩数据源：探活按表是否存在判定（缺表抛"表不存在"）；DDL 语句记录到 executedSql，
     * ddlEffective=true 时按 CREATE TABLE 语句把表标记为已建（模拟真实建表）。
     */
    private static DataSource dataSourceStub(String productName, boolean tableExists, boolean ddlEffective,
                                            List<String> executedSql) {
        Set<String> existing = new HashSet<>();
        if (tableExists) {
            existing.add(TABLE);
        }
        return (DataSource) Proxy.newProxyInstance(IdempotencyTableCheckTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return defaultReturn(proxy, method, args);
                    }
                    return Proxy.newProxyInstance(IdempotencyTableCheckTest.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (conn, connMethod, connArgs) -> {
                                switch (connMethod.getName()) {
                                    case "getMetaData":
                                        return metaDataStub(productName);
                                    case "createStatement":
                                        return statementStub(existing, ddlEffective, executedSql);
                                    default:
                                        return defaultReturn(conn, connMethod, connArgs);
                                }
                            });
                });
    }

    /** 数据库元数据桩：只提供产品名（方言识别用） */
    private static DatabaseMetaData metaDataStub(String productName) {
        return (DatabaseMetaData) Proxy.newProxyInstance(IdempotencyTableCheckTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (md, method, args) ->
                        "getDatabaseProductName".equals(method.getName())
                                ? productName : defaultReturn(md, method, args));
    }

    /** 建表语句里的表名（含 MySQL 反引号） */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?[`\"]?(\\w+)[`\"]?", Pattern.CASE_INSENSITIVE);

    /** Statement 桩：探活 + 建表脚本执行（ScriptUtils 用 execute，必须返回原始类型合法值） */
    private static Statement statementStub(Set<String> existing, boolean ddlEffective, List<String> executedSql) {
        return (Statement) Proxy.newProxyInstance(IdempotencyTableCheckTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (stmt, method, args) -> {
                    switch (method.getName()) {
                        case "executeQuery": {
                            String sql = (String) args[0];
                            executedSql.add(sql);
                            String table = sql.substring("SELECT 1 FROM ".length(), sql.indexOf(" WHERE"));
                            if (!existing.contains(table)) {
                                throw new SQLException("Table '" + table + "' doesn't exist");
                            }
                            return null;
                        }
                        case "execute": {
                            String sql = (String) args[0];
                            executedSql.add(sql);
                            if (ddlEffective) {
                                Matcher matcher = CREATE_TABLE.matcher(sql);
                                if (matcher.find()) {
                                    existing.add(matcher.group(1).toLowerCase(Locale.ROOT));
                                }
                            }
                            return false;
                        }
                        default:
                            return defaultReturn(stmt, method, args);
                    }
                });
    }

    /** 从 ApplicationContext 取 Bean 的 ObjectProvider */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(IdempotencyTableCheckTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> defaultReturn(proxy, method, args));
    }

    /** 未知方法返回类型默认值（close 等生命周期方法为无操作），仅 Object 方法给出合理实现 */
    private static Object defaultReturn(Object proxy, Method method, Object[] args) {
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
                // ScriptUtils 会读 execute 返回值/更新计数等原始类型，必须给合法默认值（不能返回 null）
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return Boolean.FALSE;
                if (type == int.class) return -1;
                if (type == long.class) return 0L;
                return null;
            }
        }
    }

    /** 拼接启动失败文案 */
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
