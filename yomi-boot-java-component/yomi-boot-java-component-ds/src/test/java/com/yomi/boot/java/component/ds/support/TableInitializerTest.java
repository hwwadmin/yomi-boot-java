package com.yomi.boot.java.component.ds.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动期建表：表齐全只探活、缺表按方言（sql/mysql|pgsql）自动执行脚本建表、方言不支持/脚本缺失/脚本失败/建后仍缺都拦启动。
 * 数据源只认主库（masterDataSource）：主库不在或只有别的名字的数据源都直接失败，不静默跳过。
 * 全程离线：stub 的 DataSource/Connection/Statement 按产品名与表名模拟"存在/不存在"，
 * 建表脚本用 src/test/resources/sql 下的真实脚本（走 Spring ScriptUtils 真实解析）。
 */
class TableInitializerTest {

    private static final String SCRIPT = "table-initializer-test.sql";

    @Test
    void passesWhenAllTablesExist() {
        Stub stub = stub("PostgreSQL");
        stub.existing().addAll(Set.of("table_init_a", "table_init_b"));

        initializer(stub, "table_init_a", "table_init_b").afterSingletonsInstantiated();

        assertEquals(2, stub.executedSql().size(), "表齐全时只探活、不执行任何 DDL");
        assertTrue(stub.executedSql().stream().allMatch(sql -> sql.startsWith("SELECT 1 FROM ")),
                "检查语句应为 WHERE 1=0 的轻量校验: " + stub.executedSql());
        assertTrue(stub.executedSql().stream().allMatch(sql -> sql.endsWith(" WHERE 1 = 0")), "不应扫描任何行");
    }

    @Test
    void createsMissingTablesByPostgresDialect() {
        Stub stub = stub("PostgreSQL");
        stub.existing().add("table_init_a");

        initializer(stub, "table_init_a", "table_init_b").afterSingletonsInstantiated();

        assertTrue(stub.existing().contains("table_init_b"), "缺的表应已创建");
        assertTrue(stub.executedSql().stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS table_init_b")),
                "必须执行 pgsql 脚本里的建表语句: " + stub.executedSql());
        assertTrue(stub.executedSql().stream().anyMatch(sql -> sql.contains("COMMENT ON TABLE table_init_b")),
                "脚本应整体执行（含索引与注释语句）: " + stub.executedSql());
    }

    @Test
    void createsMissingTablesByMysqlDialect() {
        Stub stub = stub("MySQL");

        initializer(stub, "table_init_a", "table_init_b").afterSingletonsInstantiated();

        assertEquals(Set.of("table_init_a", "table_init_b"), stub.existing(), "缺的表应已按 mysql 脚本创建");
        assertTrue(stub.executedSql().stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS `table_init_a`")),
                "必须执行 mysql 脚本（反引号语法）: " + stub.executedSql());
    }

    @Test
    void failsFastWhenDialectUnsupported() {
        Stub stub = stub("Oracle");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> initializer(stub, "table_init_a", "table_init_b").afterSingletonsInstantiated());

        assertTrue(ex.getMessage().contains("缺少表") && ex.getMessage().contains("table_init_a")
                && ex.getMessage().contains("table_init_b"), "报错必须点名缺失的表: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("不支持自动建表"), "必须说明方言不支持: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("sql/mysql/" + SCRIPT) && ex.getMessage().contains("sql/pgsql/" + SCRIPT),
                "必须给出两个方言的脚本路径: " + ex.getMessage());
    }

    @Test
    void failsFastWhenDialectScriptMissing() {
        Stub stub = stub("PostgreSQL");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TableInitializer.onMaster(beanFactoryOf(dataSource(stub)), "测试组件", "not-exists.sql",
                        List.of("table_init_a")).afterSingletonsInstantiated());

        assertTrue(ex.getMessage().contains("sql/pgsql/not-exists.sql"), "必须给出缺失的脚本路径: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PostgreSQL"), "必须说明当前数据库: " + ex.getMessage());
    }

    @Test
    void failsFastWhenScriptExecutionFails() {
        Stub stub = stub("PostgreSQL");
        stub.failOnDdl(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> initializer(stub, "table_init_a", "table_init_b").afterSingletonsInstantiated());

        assertTrue(ex.getMessage().contains("执行失败"), "必须说明脚本执行失败: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("sql/pgsql/" + SCRIPT), "必须给出脚本路径: " + ex.getMessage());
        assertNotNull(ex.getCause(), "原始 SQL 错误应作为 cause 保留");
    }

    @Test
    void failsFastWhenTableStillMissingAfterScript() {
        Stub stub = stub("PostgreSQL");
        stub.ddlCreatesTables(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> initializer(stub, "table_init_a", "table_init_b").afterSingletonsInstantiated());

        assertTrue(ex.getMessage().contains("仍缺表"), "必须说明建表后仍缺: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("sql/pgsql/" + SCRIPT), "必须给出脚本路径: " + ex.getMessage());
    }

    @Test
    void logsTableNamesInsteadOfCount() {
        Stub stub = stub("PostgreSQL");
        stub.existing().addAll(Set.of("table_init_a", "table_init_b"));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(TableInitializer.class);
        logger.addAppender(appender);
        try {
            initializer(stub, "table_init_a", "table_init_b").afterSingletonsInstantiated();
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertTrue(logs.contains("table_init_a") && logs.contains("table_init_b"), "通过日志必须列出表名: " + logs);
        assertFalse(logs.contains("张"), "通过日志不应输出表的数量: " + logs);
    }

    @Test
    void failsFastWhenConnectionUnavailable() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("connection refused");
                    }
                    return defaultReturn(proxy, method, args);
                });
        TableInitializer initializer = TableInitializer.onMaster(beanFactoryOf(broken), "测试组件", SCRIPT,
                List.of("table_init_a"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("无法获取数据库连接"), ex.getMessage());
    }

    /** 主库不在（没配 spring.datasource.url）：失败并给出路，不静默跳过（建在哪个库不容含糊） */
    @Test
    void failsFastWhenMasterDataSourceAbsent() {
        TableInitializer initializer = TableInitializer.onMaster(new DefaultListableBeanFactory(), "测试组件", SCRIPT,
                List.of("table_init_a"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("masterDataSource"), "要点名主库 Bean: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("spring.datasource.url"), "要给出出路: " + ex.getMessage());
    }

    /** 只认主库名：别的名字的数据源（附加库形态）不会被当成主库 */
    @Test
    void otherNamedDataSourceIsNotMaster() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("reportDataSource", dataSource(stub("MySQL")));
        TableInitializer initializer = TableInitializer.onMaster(beanFactory, "测试组件", SCRIPT, List.of("table_init_a"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("reportDataSource"), "要列出候选: " + ex.getMessage());
    }

    /** 建表脚本中的 CREATE TABLE 表名（含 MySQL 反引号） */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?[`\"]?(\\w+)[`\"]?", Pattern.CASE_INSENSITIVE);

    private static TableInitializer initializer(Stub stub, String... tables) {
        return TableInitializer.onMaster(beanFactoryOf(dataSource(stub)), "测试组件", SCRIPT, List.of(tables));
    }

    /** 桩：方言产品名 + 已存在表集合（缺表时 executeQuery 抛"表不存在"） */
    private static Stub stub(String productName) {
        return new Stub(productName, new HashSet<>(), new ArrayList<>(), new boolean[]{false, true});
    }

    /** 桩数据源：探活按 existing 判定；DDL 执行按 flags[failOnDdl, ddlCreatesTables] 模拟 */
    private static DataSource dataSource(Stub stub) {
        return (DataSource) Proxy.newProxyInstance(TableInitializerTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return defaultReturn(proxy, method, args);
                    }
                    return Proxy.newProxyInstance(TableInitializerTest.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (conn, connMethod, connArgs) -> {
                                switch (connMethod.getName()) {
                                    case "createStatement":
                                        return statement(stub);
                                    case "getMetaData":
                                        return metaData(stub);
                                    default:
                                        return defaultReturn(conn, connMethod, connArgs);
                                }
                            });
                });
    }

    private static DatabaseMetaData metaData(Stub stub) {
        return (DatabaseMetaData) Proxy.newProxyInstance(TableInitializerTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (md, method, args) ->
                        "getDatabaseProductName".equals(method.getName())
                                ? stub.productName() : defaultReturn(md, method, args));
    }

    private static Statement statement(Stub stub) {
        return (Statement) Proxy.newProxyInstance(TableInitializerTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (stmt, method, args) -> {
                    switch (method.getName()) {
                        case "executeQuery": {
                            String sql = (String) args[0];
                            stub.executedSql().add(sql);
                            String table = sql.substring("SELECT 1 FROM ".length(), sql.indexOf(" WHERE"));
                            if (!stub.existing().contains(table)) {
                                throw new SQLException("Table '" + table + "' doesn't exist");
                            }
                            return null;
                        }
                        case "execute": {
                            String sql = (String) args[0];
                            stub.executedSql().add(sql);
                            if (stub.flags()[0]) {
                                throw new SQLException("syntax error at or near ...");
                            }
                            if (stub.flags()[1]) {
                                Matcher matcher = CREATE_TABLE.matcher(sql);
                                if (matcher.find()) {
                                    stub.existing().add(matcher.group(1).toLowerCase(Locale.ROOT));
                                }
                            }
                            return false;
                        }
                        default:
                            return defaultReturn(stmt, method, args);
                    }
                });
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
                // ScriptUtils 会读 execute 的返回值/更新计数等原始类型，必须给合法默认值（不能返回 null）
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return Boolean.FALSE;
                if (type == int.class) return -1;
                if (type == long.class) return 0L;
                return null;
            }
        }
    }

    /** 主库形态的 BeanFactory：数据源注册成 masterDataSource（组件只认这个名字，不猜唯一/@Primary） */
    private static ListableBeanFactory beanFactoryOf(DataSource dataSource) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton(DsMaster.DATASOURCE_BEAN, dataSource);
        return beanFactory;
    }

    /** 桩状态：产品名、已存在表、执行记录、[建表语句是否报错, 是否真的建表] */
    private record Stub(String productName, Set<String> existing, List<String> executedSql, boolean[] flags) {

        void failOnDdl(boolean fail) {
            flags[0] = fail;
        }

        void ddlCreatesTables(boolean creates) {
            flags[1] = creates;
        }
    }

}
