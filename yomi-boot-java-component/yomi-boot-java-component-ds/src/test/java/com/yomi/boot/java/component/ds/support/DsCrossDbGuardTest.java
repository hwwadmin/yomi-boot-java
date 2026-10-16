package com.yomi.boot.java.component.ds.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨库访问守卫：事务绑定了别的库时记 ERROR（语句不在该事务里、提交回滚都不受控）；
 * 无事务、本库就是事务绑定的库、事务没绑任何库（如纯 Redis 事务）时一律不报；开关可关、非法值启动失败。
 */
class DsCrossDbGuardTest {

    private final DataSource master = dataSource("master");
    private final DataSource report = dataSource("report");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void setUp() {
        DsCrossDbGuard.setEnabled(true);
        resetTransactionState();
        logger = (Logger) LoggerFactory.getLogger(DsCrossDbGuard.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        DsCrossDbGuard.setEnabled(true);
        resetTransactionState();
    }

    /** 事务绑 master，语句走 report ⇒ 跨库，ERROR */
    @Test
    void reportsWhenStatementRunsOutsideTheActiveTransaction() throws Throwable {
        bindTransactionTo(master);

        Object result = DsCrossDbGuard.of("report", report).intercept(invocation("com.acme.ReportMapper.count"));

        assertEquals(List.of(), result, "守卫只记日志，不得改变执行结果");
        String errors = errors();
        assertTrue(errors.contains("跨库访问"), errors);
        assertTrue(errors.contains("com.acme.ReportMapper.count"), "要点名被拦截的语句: " + errors);
        assertTrue(errors.contains("report"), "要点名本语句所在的库: " + errors);
    }

    /** 事务绑的就是本库：正常写入，不报 */
    @Test
    void staysSilentWhenStatementParticipatesInTheTransaction() throws Throwable {
        bindTransactionTo(master);

        DsCrossDbGuard.of("master", master).intercept(invocation("com.acme.OrderMapper.insert"));

        assertEquals("", errors());
    }

    /** 没有事务：不报（守卫只看"事务内的跨库误用"） */
    @Test
    void staysSilentWithoutTransaction() throws Throwable {
        DsCrossDbGuard.of("report", report).intercept(invocation("com.acme.ReportMapper.count"));

        assertEquals("", errors());
    }

    /** 事务活跃但没绑定任何数据源（如纯 Redis 事务）：不算跨库，不报 */
    @Test
    void staysSilentWhenTransactionBoundNoDataSource() throws Throwable {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty(), "前置条件：事务没绑任何连接");

        DsCrossDbGuard.of("report", report).intercept(invocation("com.acme.ReportMapper.count"));

        assertEquals("", errors());
    }

    /** 开关 off：不报 */
    @Test
    void staysSilentWhenDisabled() throws Throwable {
        DsCrossDbGuard.setEnabled(false);
        bindTransactionTo(master);

        DsCrossDbGuard.of("report", report).intercept(invocation("com.acme.ReportMapper.count"));

        assertEquals("", errors());
    }

    /** 开关取值：error/空=开，off=关，其余 fail-fast */
    @Test
    void parsesSwitchValues() {
        assertTrue(DsCrossDbGuard.parseEnabled(null));
        assertTrue(DsCrossDbGuard.parseEnabled(""));
        assertTrue(DsCrossDbGuard.parseEnabled("ERROR"));
        assertFalse(DsCrossDbGuard.parseEnabled("off"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> DsCrossDbGuard.parseEnabled("warn"));
        assertTrue(ex.getMessage().contains("yomi.boot.ds.transaction-guard"), ex.getMessage());
        assertTrue(ex.getMessage().contains("off"), "报错要列出合法取值: " + ex.getMessage());
    }

    /** 库名可读（自检与诊断展示用） */
    @Test
    void exposesLibraryName() {
        assertEquals("report", DsCrossDbGuard.of("report", report).libraryName());
    }

    /** 复位线程级事务状态：注意 {@code TransactionSynchronizationManager.clear()} 不会解绑 resources，必须显式 unbind */
    private static void resetTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        new ArrayList<>(TransactionSynchronizationManager.getResourceMap().keySet())
                .forEach(TransactionSynchronizationManager::unbindResource);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
    }

    /** 把事务绑定到指定数据源（模拟 DataSourceTransactionManager.doBegin 之后的形态：同步 + 实际事务标记 + 连接持有者） */
    private static void bindTransactionTo(DataSource dataSource) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection()));
    }

    /** 构造一次 Executor.query 调用（守卫只读 args[0] 的语句标识；MyBatis 的 Invocation 要求方法是插件目标接口的） */
    private static Invocation invocation(String statementId) throws NoSuchMethodException {
        SqlSource sqlSource = parameter -> new BoundSql(new org.apache.ibatis.session.Configuration(),
                "select 1", List.<org.apache.ibatis.mapping.ParameterMapping>of(), null);
        MappedStatement statement = new MappedStatement.Builder(new org.apache.ibatis.session.Configuration(),
                statementId, sqlSource, SqlCommandType.SELECT).build();
        Method method = Executor.class.getMethod("query", MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class);
        Object executor = Proxy.newProxyInstance(DsCrossDbGuardTest.class.getClassLoader(),
                new Class<?>[]{Executor.class}, (proxy, invoked, args) ->
                        "query".equals(invoked.getName()) ? List.of() : defaultValue(invoked.getReturnType()));
        return new Invocation(executor, method, new Object[]{statement, null, RowBounds.DEFAULT, null});
    }

    /** 原始类型默认值（代理桩用，避免 NPE 拆箱） */
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return Boolean.TRUE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == List.class) return List.of();
        return null;
    }

    private String errors() {
        return appender.list.stream()
                .filter(event -> event.getLevel().toString().equals("ERROR"))
                .map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(DsCrossDbGuardTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit", "isClosed", "isReadOnly", "isValid" -> Boolean.TRUE;
                    case "getTransactionIsolation" -> 0;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static DataSource dataSource(String label) {
        return (DataSource) Proxy.newProxyInstance(DsCrossDbGuardTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> label;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                });
    }

}
