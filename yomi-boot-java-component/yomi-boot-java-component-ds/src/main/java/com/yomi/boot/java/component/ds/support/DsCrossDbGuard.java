package com.yomi.boot.java.component.ds.support;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 跨库访问守卫：一个事务里访问了"不是该事务绑定的那个库"，记 ERROR
 * <p>
 * 静态分包下没有运行期路由，但"在一个 {@code @Transactional} 里用两个库的 Mapper"仍是容易写错的形态，
 * 后果也不显眼：MyBatis 取连接走 {@code DataSourceUtils.getConnection(本 Mapper 所在库)}，
 * 事务绑的是别的库时本库拿到的是另一条连接，语句不在该事务里（提交/回滚都不受控）。
 * 本拦截器把这种误用变成一条显式日志。
 * <p>
 * 形态：每个工厂一个实例（绑定该工厂的库名与数据源），因为判定需要知道"本语句属于哪个库"：
 * {@code DsCrossDbGuard.of(库名, 该工厂的数据源)}。主库由本组件自动挂上；附加库由业务按
 * 《组件使用说明》的模板挂（一行 {@code factory.setPlugins(chain, DsCrossDbGuard.of(...))}）。
 * <p>
 * "当前线程的事务绑在哪个库"这条判据由 {@link #otherBoundLibraries(javax.sql.DataSource)} 对外复用
 * （幂等组件用它做"外层事务必须落主库"的入口自检）。
 * <p>
 * 判定：事务活跃 + 本库不是事务绑定的库（{@code hasResource(本库)} 为假）+ 事务确实绑定了其它库的
 * 连接 ⇒ ERROR。无事务或本库就是事务绑定的库时只做两次 ThreadLocal 读，零额外开销。
 * <p>
 * 覆盖边界：只覆盖经 MyBatis 的语句，且只覆盖挂了本守卫的工厂；
 * {@code JdbcTemplate}、原生 JDBC、未挂守卫的自建工厂不在覆盖内。
 * <p>
 * 开关：{@code yomi.ds.transaction-guard=error|off}（默认 {@code error}）。ds 装配时把它设进
 * 静态开关，业务按模板创建的守卫实例同样受它控制；只影响日志诊断，不影响任何执行路径。
 *
 * @author zwei
 */
@Slf4j
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class})})
public class DsCrossDbGuard implements Interceptor {

    /** 守卫开关（ds 装配时按配置设置；业务按模板创建的实例共用同一开关） */
    private static volatile boolean enabled = true;

    /** 本工厂所属的库名（日志展示） */
    private final String libraryName;

    /** 本工厂的数据源（与事务管理器绑定的是同一个实例） */
    private final DataSource dataSource;

    private DsCrossDbGuard(String libraryName, DataSource dataSource) {
        this.libraryName = libraryName;
        this.dataSource = dataSource;
    }

    /**
     * 为某个工厂创建守卫（业务自建附加库工厂用这个；主库由本组件内部创建）
     *
     * @param libraryName 该工厂所属库名（建议用数据源名，便于定位）
     * @param dataSource  该工厂的数据源实例（必须是事务管理器绑定的同一个实例）
     */
    public static DsCrossDbGuard of(String libraryName, DataSource dataSource) {
        return new DsCrossDbGuard(libraryName, dataSource);
    }

    /** 解析 {@code yomi.ds.transaction-guard}：{@code error}（默认，开）/ {@code off}（关）；非法值启动失败 */
    public static boolean parseEnabled(String mode) {
        if (mode == null || mode.isBlank() || "error".equalsIgnoreCase(mode.trim())) {
            return true;
        }
        if ("off".equalsIgnoreCase(mode.trim())) {
            return false;
        }
        throw new IllegalStateException("yomi.ds.transaction-guard 取值非法: [" + mode
                + "]，合法取值: error（默认，跨库访问记 ERROR）、off（关闭）");
    }

    /** 设置开关（由 ds 装配时调用；只影响日志诊断） */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 本守卫所属库名（诊断与自检展示用） */
    public String libraryName() {
        return libraryName;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        check(statementId(invocation));
        return invocation.proceed();
    }

    /** 事务活跃、本库不是事务绑定的库、且事务绑定了别的库的连接 ⇒ 跨库误用 */
    private void check(String statementId) {
        if (!enabled || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        if (TransactionSynchronizationManager.hasResource(dataSource)) {
            return;
        }
        String bound = otherBoundLibraries(dataSource);
        if (bound.isEmpty()) {
            return;
        }
        log.error("[ds] 跨库访问：当前事务绑定的是其它数据源（{}），而语句 {} 走 {}——"
                        + "该语句不在这个事务里，提交/回滚都不受控（连接池默认 autoCommit=true 时表现为即时生效、回滚撤不回来）。"
                        + "一个事务只碰一个库：跨库一致性请用事件/消息编排（component-event），"
                        + "或给非主库显式指定 transactionManager（见《组件使用说明》多数据源规范）。",
                bound, statementId, libraryName);
    }

    /**
     * 当前线程事务绑定的、不属于 {@code own} 的数据源（连接持有者），多个用逗号连接；空串 = 没有别的库在这个事务里。
     * <p>
     * 只认 {@link ConnectionHolder}：事务活跃但没绑任何数据源连接（如纯 Redis 事务）时返回空串，不算跨库。
     * 判定"本库的语句在不在这个事务里"就靠它——{@code hasResource(本库)} 为假时，任何连接持有者都属于别的库。
     *
     * @param own 本库的数据源实例（事务管理器绑定时用的就是它）
     */
    public static String otherBoundLibraries(DataSource own) {
        return TransactionSynchronizationManager.getResourceMap().entrySet().stream()
                .filter(entry -> entry.getValue() instanceof ConnectionHolder)
                .filter(entry -> entry.getKey() != own)
                .map(entry -> entry.getKey() instanceof DataSource bound
                        ? bound.getClass().getSimpleName() : String.valueOf(entry.getKey()))
                .collect(Collectors.joining(", "));
    }

    /**
     * 当前线程事务绑定的数据源（连接持有者对应的那些）；空列表 = 没有数据源事务在跑。
     * <p>与 {@link #otherBoundLibraries} 同一判据，供"按事务判定记录写哪个库"的组件复用（如幂等守卫）。
     */
    public static List<DataSource> boundDataSources() {
        return TransactionSynchronizationManager.getResourceMap().entrySet().stream()
                .filter(entry -> entry.getValue() instanceof ConnectionHolder)
                .map(entry -> entry.getKey())
                .filter(DataSource.class::isInstance)
                .map(DataSource.class::cast)
                .toList();
    }

    /** 被拦截语句的标识（mapper 接口#方法） */
    private static String statementId(Invocation invocation) {
        Object[] args = invocation.getArgs();
        return args != null && args.length > 0 && args[0] instanceof MappedStatement statement
                ? statement.getId() : "unknown";
    }

}
